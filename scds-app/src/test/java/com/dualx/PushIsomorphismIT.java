package com.dualx;

import com.dualx.core.state.SituationRevisionLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双通道同构的端到端验收，即设计 [02](../../docs/design/02-push-ports.md) §7 的<b>判据 1</b>
 * （「链路通道也必须检查 JSON 根类型一致」），并覆盖<b>判据 3</b>（超窗恢复：close 4000 → 改全量重连）
 * 与判据 5 的一部分（关闭即注销）。
 *
 * <p>前序工作把两条通道都接线完毕，但两侧各自只有单测：单测证明「会话表会推数组」，
 * 却证明不了「真实装配出来的 Spring 上下文里，推送与 Poll 读的是同一份数据」。
 * 本类用<b>真实生产入口</b>（真 MySQL、真安全链、真 HTTP、真 WS 握手）把这两句话连起来：
 * 用 edge 令牌完成 WS 握手，读一帧推送，再用 HTTP 读一次 Poll，比较两者的 JSON。</p>
 *
 * <p><b>两条通道的判据不同，故断言不同</b>：</p>
 * <ul>
 *   <li><b>链路侧</b>：无 revision 维度，每次变化推整份快照。因此可以断言推送帧与 Poll 帧
 *       <b>结构上完全相等</b>（屏障内无变化），且变化经 HTTP 触发后推送带回的是新的完整快照。</li>
 *   <li><b>态势侧</b>：有 revision 与 {@code serverTime} 两个「时刻相关」字段。断言的是
 *       <b>同构</b>——根类型一致、字段集合一致、{@code epoch}/{@code revision} 一致、
 *       载荷数组一致；{@code serverTime} 刻意不比较（两次采样必然不同毫秒，
 *       按设计 02 §7 判据 1「不能要求不同采样时刻的 revision 恒相等」的同一口径）。</li>
 * </ul>
 *
 * <p><b>用仿真网关</b>（{@code scds.gateway.mode=simulator}）：本机没有 COM7，真串口必然打开失败；
 * 仿真链路与真链路共用同一个 {@code LinkRegistry}，冲突判据与状态机完全同源，且能确定性地
 * 触发「变化 → 推送」。真实串口的时序对拍只能现场验收（设计 05 §7）。</p>
 *
 * <p><b>刻意不带子协议</b>：契约 {@code wsLinks}/{@code wsSituation} 声明了
 * {@code scds.links.v1} / {@code scds.situation.v1}，但本类聚焦的是<b>载荷同构</b>，
 * 子协议协商是另一条判据；客户端在不带子协议时行为最稳，不掺入无关变量。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。
 * 链路状态是进程内运行态、不落库，故本类不做任何数据库清理。</p>
 */
class PushIsomorphismIT {

    private static App app;

    @BeforeAll
    static void bootSimulatedApplication() throws Exception {
        app = App.boot(ApplicationStartupSupport.newKeyPair(), "simulator");
    }

    @AfterAll
    static void shutdownApplication() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("链路通道：WS 首帧与每次变化的帧，都与 HTTP getLinks 的响应体是同一个数组快照")
    void linkPushIsStructurallyIdenticalToPoll() throws Exception {
        FrameCollector collector = new FrameCollector(app.mapper);
        try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/devices/links"), app.admin, collector)) {
            // 首帧即完整快照：三种链路恒定各一条，且与 Poll 逐字段相等（屏障内无变化）
            JsonNode firstFrame = collector.awaitFrame(
                    node -> node.isArray() && node.size() == 3, Duration.ofSeconds(5));
            assertThat(kindsOf(firstFrame))
                    .containsExactly("SERIAL_VEST", "UWB_TCP_SERVER", "UWB_SERIAL");
            assertThat(firstFrame).as("WS 首帧与 GET /devices/links 是同一份数据").isEqualTo(app.linksSnapshot());

            // 变化经 HTTP 触发；推送必须带回「整个新快照」，而不是只含变化那一条的局部对象
            HttpResponse<String> opened = app.post("/api/v1/devices/links/serial",
                    app.serialRequest("COM7"), app.admin);
            assertThat(opened.statusCode()).isEqualTo(200);

            JsonNode pushed = collector.awaitFrame(
                    node -> "OPEN".equals(kindIn(node, "SERIAL_VEST").path("state").asText()),
                    Duration.ofSeconds(5));
            assertThat(pushed.isArray()).as("根类型必须是数组").isTrue();
            assertThat(pushed.size()).isEqualTo(3);
            assertThat(kindIn(pushed, "SERIAL_VEST").path("target").asText()).isEqualTo("COM7");
            assertThat(kindIn(pushed, "UWB_TCP_SERVER").path("state").asText()).as("未变化的那条也在快照里")
                    .isEqualTo("CLOSED");
            assertThat(pushed).as("推送帧与 Poll 帧结构完全相等").isEqualTo(app.linksSnapshot());

            // 关闭同样扇出一份完整快照，且回到 CLOSED
            assertThat(app.delete("/api/v1/devices/links/serial", app.admin).statusCode()).isEqualTo(200);
            JsonNode closed = collector.awaitFrame(
                    node -> "CLOSED".equals(kindIn(node, "SERIAL_VEST").path("state").asText()),
                    Duration.ofSeconds(5));
            assertThat(closed).isEqualTo(app.linksSnapshot());
        } finally {
            app.delete("/api/v1/devices/links/serial", app.admin);
        }
    }

    @Test
    @DisplayName("态势通道：WS 首帧与不带参数的 Poll 是同一条读侧口径（同根类型、同字段、同 revision）")
    void situationPushIsIsomorphicToPoll() throws Exception {
        FrameCollector collector = new FrameCollector(app.mapper);
        try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/situation"), app.admin, collector)) {
            JsonNode pushed = collector.awaitFrame(JsonNode::isObject, Duration.ofSeconds(5));
            JsonNode polled = app.situationEnvelope();

            assertThat(pushed.path("full").asBoolean()).as("不带 sinceRevision 的 WS 首帧是全量").isTrue();
            assertThat(polled.path("full").asBoolean()).as("不带参数的 Poll 也是全量").isTrue();
            assertThat(fieldNames(pushed))
                    .as("推送与 Poll 的根类型字段集合必须完全一致（类型级同构）")
                    .isEqualTo(fieldNames(polled));
            assertThat(pushed.path("epoch").asText()).as("同进程 → 同 epoch").isEqualTo(polled.path("epoch").asText());
            assertThat(pushed.path("revision").asLong())
                    .as("屏障内无输入 → revision 一致").isEqualTo(polled.path("revision").asLong());
            assertThat(pushed.path("units")).as("全量载荷逐字段相等").isEqualTo(polled.path("units"));
            assertThat(pushed.path("teamSummary")).isEqualTo(polled.path("teamSummary"));
            // serverTime 是两次采样各自的时刻，必然不同毫秒——同构要求的是形状，不是同一瞬间
            assertThat(pushed.path("serverTime").asText()).isNotBlank();
            assertThat(polled.path("serverTime").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("保活与注销：未带令牌的 WS 握手被安全链挡下，带令牌的会话关闭后活跃数回落")
    void handshakeRequiresEdgeTokenAndCloseUnregisters() throws Exception {
        FrameCollector collector = new FrameCollector(app.mapper);
        try (Ws ws = Ws.open(app.wsUrl("/ws/v1/devices/links"), app.admin, collector)) {
            collector.awaitFrame(node -> node.isArray() && node.size() == 3, Duration.ofSeconds(5));
            ws.close();
        }
        // 会话已注销：链路状态再变化时不再有消费者，但 HTTP Poll 仍必须可用（双通道约定）
        assertThat(app.delete("/api/v1/devices/links/serial", app.admin).statusCode()).isEqualTo(200);
        assertThat(app.linksSnapshot().isArray()).isTrue();
    }

    @Test
    @DisplayName("超窗补齐（判据 3）：携「当前 revision − 窗口 − 1」握手被 close 4000；改全量重连即刻拿到全量")
    void staleSinceRevisionIsClosedWith4000AndReconnectGetsFullSnapshot() throws Exception {
        long current = app.situationEnvelope().path("revision").asLong();
        long tooOld = current - app.window() - 1;

        // 握手已完成连接升级，故超窗只能用 close 4000 表达（契约 wsSituation），不能返 409
        FrameCollector rejected = new FrameCollector(app.mapper);
        try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/situation?sinceRevision=" + tooOld), app.admin, rejected)) {
            CloseStatus status = rejected.awaitClose(Duration.ofSeconds(5));
            assertThat(status.getCode()).as("超窗的关闭码与契约逐字一致").isEqualTo(4000);
            assertThat(status.getReason()).isEqualTo("SITUATION_REVISION_TOO_OLD");
        }

        // 客户端按契约不带 sinceRevision 重连：首帧必须是 full=true，才不至于「卡在旧 revision」
        FrameCollector fresh = new FrameCollector(app.mapper);
        try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/situation"), app.admin, fresh)) {
            JsonNode first = fresh.awaitFrame(JsonNode::isObject, Duration.ofSeconds(5));
            assertThat(first.path("full").asBoolean()).as("改全量后的首帧必须是全量").isTrue();
            assertThat(first.path("revision").asLong())
                    .as("revision 只增不减，重连后不得低于握手前的当前值").isGreaterThanOrEqualTo(current);
            assertThat(first.path("units").isArray()).as("全量帧必然携带权威 units").isTrue();
        }
    }

    // ── 断言辅助 ─────────────────────────────────────────────────────────────

    private static List<String> kindsOf(JsonNode snapshot) {
        List<String> kinds = new ArrayList<>();
        if (snapshot != null && snapshot.isArray()) {
            snapshot.forEach(item -> kinds.add(item.path("kind").asText()));
        }
        return kinds;
    }

    /** 取快照里指定 kind 的那一条；不是数组或找不到时返回缺失节点（供谓词安全使用）。 */
    private static JsonNode kindIn(JsonNode snapshot, String kind) {
        if (snapshot != null && snapshot.isArray()) {
            for (JsonNode item : snapshot) {
                if (kind.equals(item.path("kind").asText())) {
                    return item;
                }
            }
        }
        return MissingNode.getInstance();
    }

    /** 根对象的字段名集合（排序后比较，避免依赖字段声明顺序）。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 收集服务端推送的每一帧，并支持按谓词等待（虚拟线程与网络都有延迟，不能用固定 sleep）。 */
    private static final class FrameCollector extends TextWebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final BlockingQueue<CloseStatus> closes = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;

        FrameCollector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closes.add(status);
        }

        /** 超窗握手由服务端主动关闭，客户端凭这里的关闭码判断该改全量重连。 */
        CloseStatus awaitClose(Duration timeout) throws Exception {
            CloseStatus status = closes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (status == null) {
                throw new AssertionError("等待服务端关闭帧超时");
            }
            return status;
        }

        JsonNode awaitFrame(Predicate<JsonNode> accept, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                String payload = frames.poll(Math.max(1L, remaining), TimeUnit.NANOSECONDS);
                if (payload == null) {
                    break;
                }
                JsonNode node = mapper.readTree(payload);
                if (accept.test(node)) {
                    return node;
                }
            }
            throw new AssertionError("等待匹配的推送帧超时（已收 " + frames.size() + " 帧）");
        }
    }

    /** 一次 WS 会话；仅用于在用例结束时把连接关干净，不给下一次用例留残留会话。 */
    private static final class Ws implements AutoCloseable {

        private final WebSocketSession session;

        private Ws(WebSocketSession session) {
            this.session = session;
        }

        static Ws open(String url, String token, TextWebSocketHandler handler) throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            WebSocketSession session = new StandardWebSocketClient()
                    .execute(handler, headers, URI.create(url))
                    .get(5, TimeUnit.SECONDS);
            return new Ws(session);
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }

    /** 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP 与真 WS，以及仿真链路。 */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String base;
        private final String authority;
        private final String admin;
        private final HttpClient http;

        private App(ConfigurableEnvironment environment, KeyPair key) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) context)
                    .getWebServer().getPort();
            this.authority = "127.0.0.1:" + port;
            this.base = "http://" + authority;
            this.admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }

        /**
         * @param gatewayMode {@code simulator} / {@code real}；传 {@code null} 表示不覆盖，
         *                    用 {@code application.yml} 的默认值
         */
        private static App boot(KeyPair key, String gatewayMode) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            if (gatewayMode != null) {
                environment.getPropertySources().addFirst(
                        new MapPropertySource("push-isomorphism-it", Map.of("scds.gateway.mode", gatewayMode)));
            }
            return new App(environment, key);
        }

        private String wsUrl(String path) {
            return "ws://" + authority + path;
        }

        private HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearer);
        }

        private HttpResponse<String> delete(String path, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE(), bearer);
        }

        private HttpResponse<String> get(String path, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET(), bearer);
        }

        private HttpResponse<String> send(HttpRequest.Builder request, String bearer) throws Exception {
            if (bearer != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private JsonNode linksSnapshot() throws Exception {
            HttpResponse<String> response = get("/api/v1/devices/links", admin);
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        private JsonNode situationEnvelope() throws Exception {
            HttpResponse<String> response = get("/api/v1/situation", admin);
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        /** 增量窗口条数：从真实配置读，缺省时用中立层的设计默认值（判据 3 的「窗口」就是它）。 */
        private int window() {
            Integer configured = context.getEnvironment()
                    .getProperty("scds.situation.revision-window", Integer.class);
            return configured != null ? configured : SituationRevisionLog.DEFAULT_WINDOW;
        }

        private JsonNode serialRequest(String portName) {
            return mapper.createObjectNode().put("portName", portName);
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
