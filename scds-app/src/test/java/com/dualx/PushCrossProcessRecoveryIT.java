package com.dualx;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.state.SituationQuery;
import com.dualx.core.support.UuidV7;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import com.dualx.protocol.Team;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨代恢复的端到端验收，即设计 [02-push-ports](../../docs/design/02-push-ports.md) §7 的<b>判据 9</b>。
 *
 * <p><b>判据原文</b>：「让新进程 revision 增长到旧 N 以上后再携旧 N 重连，仍必须恢复全量，不能把另一个
 * 进程的 revision 当成当前窗口依据。」</p>
 *
 * <p><b>为什么必须真起两个进程</b>：这条判据的全部难点在于「revision 只在<b>单代内</b>单调，重启归零
 * 后与旧代数值<b>重叠</b>」（设计 02 §4.4、§6 Q8）。在同一个 Spring 上下文里做任何断言都验不到它——
 * 只有先后起两个完整上下文（两枚 {@code epoch}），才会出现「旧 N 落在新进程的数值窗口内、
 * 数值检查无法辨别」的现场。本类因此刻意不复用 {@code PushSituationRecoveryIT} 的夹具：那一类
 * 只有一代，它的注释也明说了「不假装覆盖判据 9」。</p>
 *
 * <p><b>两代怎么来</b>：{@code ApplicationStartupSupport.start()} 起的是**完整生产上下文**（真 MySQL、
 * 真安全链、真 HTTP/WS、真 Flyway）；关掉一代、再起一代，就是同一 JVM 内的两次「进程启动」。
 * 两代各带一枚 {@code epoch}（{@code SituationStateService} 构造时生成）与各自的 {@code revision}
 * （进程内 {@code AtomicLong}，不持久化），因此两代之间的数值重叠是真实发生的，不是模拟出来的。</p>
 *
 * <p><b>本类证明的三件事</b>：① 换代必换 {@code epoch}；② 携旧代 N 续订时，新进程会按<u>自己的</u>
 * 序号补齐——数值上「在窗口内」，客户端若只看 revision 就会把另一个进程的窗口当成自己的依据，
 * 唯一能识破的是帧自报的 {@code epoch}；③ 客户端按契约改用「不带 {@code sinceRevision}」重连后，
 * 拿到的是新进程自己的权威全量（与 Poll 同构、场次与编成都是新代的）。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。</p>
 */
class PushCrossProcessRecoveryIT {

    // ── 协议黄金样本（与 SituationStateIT 同源，未复算） ─────────────────────────

    /** {@code ff03} 短版心跳：马甲 2 / 弹药 30/30 / HP 100 → 离线转 LIVE。 */
    private static final String HEARTBEAT_VEST2_LIVE = "ff03000002001e64001ea4affa";

    /** {@code ff04} 室外 GPS：马甲 2 → 定位变化。重复投喂每次稳定推高一位 revision（阵亡才冻结坐标）。 */
    private static final String GPS_VEST2 = "ff0400000207136d12017e8822c7affa";

    /** {@code ff03} 短版心跳：马甲 2 / HP 0 → 阵亡冻结，供旧代留下一个与新代不同的终态。 */
    private static final String HEARTBEAT_VEST2_DIE = "ff03000002000000000004affa";

    /** 每代自己的编成：2 是唯一被驱动的马甲，其余用于凑出「装载了 N 个单元」。 */
    private static final List<RosterRow> ROSTER = List.of(
            new RosterRow(1, "红军-01", Team.RED),
            new RosterRow(2, "红军-02", Team.RED),
            new RosterRow(4, "蓝军-04", Team.BLUE),
            new RosterRow(5, "蓝军-05", Team.BLUE));

    private static final String PORT_NAME = "COM7";

    /** 新进程必须把 revision 长过旧 N：注入上限只是防呆，正常情况几次就到。 */
    private static final int MAX_INJECTIONS = 64;

    private static KeyPair keyPair;
    private static String admin;

    @BeforeAll
    static void keyAndToken() throws Exception {
        keyPair = ApplicationStartupSupport.newKeyPair();
        admin = ApplicationStartupSupport.externalAccessToken(keyPair, List.of("ADMIN"));
    }

    @Test
    @DisplayName("跨进程恢复（判据 9）：新进程 revision 长过旧 N 后携旧 N 重连——帧自报新代，客户端据此丢弃缓存改全量")
    void reconnectWithOldGenerationRevisionStillRecoversFullState() throws Exception {
        // ── 第一代进程：跑一场演习，把 revision 推到 N，客户端断开时手里握着 (epochA, N) ──
        String oldEpoch;
        long oldRevision;
        App old = App.boot(keyPair);
        try {
            old.openSerialLink();
            old.startExercise();
            old.injectAndAwaitBump(HEARTBEAT_VEST2_LIVE);
            old.injectAndAwaitBump(GPS_VEST2);
            old.injectAndAwaitBump(HEARTBEAT_VEST2_DIE);
            oldRevision = old.query.revision();

            FrameCollector collector = new FrameCollector(old.mapper);
            try (Ws ignored = Ws.open(old.wsUrl("/ws/v1/situation"), admin, collector)) {
                JsonNode frame = collector.awaitFrame(PushCrossProcessRecoveryIT::isEnvelope, Duration.ofSeconds(5));
                assertThat(frame.path("full").asBoolean()).as("不带 sinceRevision → 全量起步").isTrue();
                assertThat(frame.path("revision").asLong()).isEqualTo(oldRevision);
                // 这就是客户端跨过一次断线后手里留着的东西：一个**旧代**的序号 + 一个旧代标识。
                oldEpoch = frame.path("epoch").asText();
                assertThat(oldEpoch).as("帧必须自报进程代标识").isNotBlank();
                assertThat(frame.path("units")).hasSize(ROSTER.size());
                assertThat(old.unitStatus(frame, 2)).as("旧代里马甲 2 以阵亡收场").isEqualTo("DIE");
            }
            old.stopExercise();
        } finally {
            old.closeAndClean();
        }

        // ── 第二代进程（新上下文＝新进程代）：revision 从 0 重新爬升，与旧代数值必然重叠 ──
        App next = App.boot(keyPair);
        try {
            next.openSerialLink();
            next.startExercise();
            String nextEpoch = next.query.epoch();
            assertThat(nextEpoch).as("换代必换标识：revision 归零后只能靠它分辨（设计 02 §6 Q8）")
                    .isNotEqualTo(oldEpoch);

            // 判据 9 的前提：让新进程的 revision 长过旧 N。此后「携旧 N 重连」在数值上完全合法，
            // 数值检查不会报超窗——这正是需要 epoch 的原因。
            for (int i = 0; i < MAX_INJECTIONS && next.query.revision() <= oldRevision; i++) {
                next.injectAndAwaitBump(GPS_VEST2);
            }
            assertThat(next.query.revision()).as("新进程 revision 必须已长过旧 N，判据 9 的场景才成立")
                    .isGreaterThan(oldRevision);

            // 客户端按老规矩「携 sinceRevision 续订」。服务端拿到的 N 落在**新进程**的窗口内，
            // 于是它会按新代的序号补齐——这些帧与客户端本地缓存毫无关系（§7 判据 9 的原文所指）。
            FrameCollector stale = new FrameCollector(next.mapper);
            try (Ws ignored = Ws.open(
                    next.wsUrl("/ws/v1/situation?sinceRevision=" + oldRevision), admin, stale)) {
                JsonNode frame = stale.awaitFrame(PushCrossProcessRecoveryIT::isEnvelope, Duration.ofSeconds(5));
                assertThat(frame.path("epoch").asText())
                        .as("不能把另一个进程的 revision 当成当前窗口依据：两代数值可以重叠、无法辨别，"
                                + "唯一能识破换代的是帧自报的 epoch（判据 9）")
                        .isEqualTo(nextEpoch)
                        .isNotEqualTo(oldEpoch);
                assertThat(frame.path("full").asBoolean())
                        .as("旧 N 落在新进程的数值窗口内，故这里是按新代序号补齐的增量帧——"
                                + "若客户端不看 epoch 就会把它合进旧代缓存")
                        .isFalse();
            }

            // 契约规定的客户端动作（契约 wsSituation「换代表达」）：发现 epoch 不符 → 丢弃本地缓存
            // → 不带 sinceRevision 重连取全量。这一步必须真的拿到新代的权威快照，判据才算闭环。
            JsonNode polled = next.situationEnvelope();
            FrameCollector recovered = new FrameCollector(next.mapper);
            try (Ws ignored = Ws.open(next.wsUrl("/ws/v1/situation"), admin, recovered)) {
                JsonNode full = recovered.awaitFrame(node -> node.path("full").asBoolean(), Duration.ofSeconds(5));
                assertThat(full.path("epoch").asText()).as("恢复后的每一帧都仍是同一代")
                        .isEqualTo(nextEpoch);
                assertThat(full.path("revision").asLong())
                        .as("改全量拿到的与 Poll 同构（同一读侧口径、同一 Java 类型）")
                        .isEqualTo(polled.path("revision").asLong());
                assertThat(full.path("running").asBoolean()).isTrue();
                assertThat(full.path("exerciseId").asText()).isEqualTo(next.exerciseId);
                assertThat(full.path("units")).as("恢复的是新进程自己的权威快照，不是旧代缓存的拼补")
                        .hasSize(ROSTER.size());
                assertThat(next.unitStatus(full, 2)).as("新代的马甲 2 未被旧代的「阵亡」污染")
                        .isNotEqualTo("DIE");
            }
        } finally {
            next.closeAndClean();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 是不是态势信封（控制帧如 PONG 没有 revision）。 */
    private static boolean isEnvelope(JsonNode node) {
        return node.has("revision");
    }

    /** 轮询到条件成立或超时：写手线程是异步消费者，断言前必须等它把事件处理完。 */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        throw new AssertionError("等待超时（10s）：" + what);
    }

    /** 编成里的一行（只用于铺库，真值仍以库读回的 RosterEntry 为准）。 */
    private record RosterRow(int vestNum, String name, Team team) {
    }

    // ── WS 夹具 ──────────────────────────────────────────────────────────────

    /** 收集服务端推送的每一帧，并支持按谓词等待（虚拟线程与网络都有延迟，不能用固定 sleep）。 */
    private static final class FrameCollector extends TextWebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final ObjectMapper mapper;

        FrameCollector(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
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
                session.close(org.springframework.web.socket.CloseStatus.NORMAL);
            }
        }
    }

    /**
     * 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP/WS，以及仿真网关。
     *
     * <p>每代自带一份想定与编成（{@link #planId} 逐代随机），收尾时各清各的——两代之间不共享夹具，
     * 所以「先关第一代、再起第二代」不会踩到对方的行。</p>
     */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String base;
        private final String authority;
        private final HttpClient http;
        private final SimulatedLinkAdapter links;
        private final JdbcClient jdbc;
        private final SituationQuery query;
        private final UUID planId = UuidV7.generate();
        private String exerciseId;

        private App(ConfigurableEnvironment environment) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.authority = "127.0.0.1:" + port;
            this.base = "http://" + authority;
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            this.links = (SimulatedLinkAdapter) context.getBean(DeviceLinkAdmin.class);
            this.jdbc = context.getBean(JdbcClient.class);
            this.query = context.getBean(SituationQuery.class);
        }

        private static App boot(KeyPair key) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "push-cross-process-recovery-it", Map.of("scds.gateway.mode", "simulator")));
            return new App(environment);
        }

        /** 投喂上行帧要求链路已开（仿真下不开链路投喂是非法用法）。 */
        private void openSerialLink() {
            links.openSerial(new LinkConfig(LinkKind.SERIAL_VEST, PORT_NAME, null,
                    false, null, null, false, null, null, null));
        }

        /** 投喂一帧并等写手线程把它变成一次 revision 递增（每次注入必须恰好推高一位）。 */
        private void injectAndAwaitBump(String frame) {
            long before = query.revision();
            links.injectSerial(frame);
            await("投喂一帧必须推高一位 revision", () -> query.revision() > before);
        }

        /** 走真 HTTP 开演（真 LIFE → 真 binder → 真状态机），而不是直接调 binder。 */
        private void startExercise() throws Exception {
            // 想定与编成必须先落库：开演入口是从 t_plan / t_plan_roster 读编成的。
            seedPlanAndRoster();
            HttpResponse<String> response = post("/api/v1/exercise/current",
                    mapper.createObjectNode().put("planId", planId.toString()), admin);
            assertThat(response.statusCode()).isEqualTo(201);
            exerciseId = mapper.readTree(response.body()).path("exerciseId").asText();
        }

        private void stopExercise() throws Exception {
            assertThat(delete("/api/v1/exercise/current", admin).statusCode()).isEqualTo(200);
        }

        private void seedPlanAndRoster() {
            jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                            + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                    .param("id", planId.toString())
                    .param("name", "跨代恢复IT想定-" + planId).update();
            for (RosterRow row : ROSTER) {
                jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                                + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                        .param("planId", planId.toString())
                        .param("vestNum", row.vestNum()).param("name", row.name())
                        .param("team", row.team().name()).update();
            }
        }

        /** 读信封里某个马甲的状态（判据里要的就是「这帧代表的是哪一代的战场」）。 */
        private String unitStatus(JsonNode envelope, int vestNum) {
            for (JsonNode unit : envelope.path("units")) {
                if (unit.path("vestNum").asInt() == vestNum) {
                    return unit.path("status").asText();
                }
            }
            throw new AssertionError("信封里没有马甲 " + vestNum);
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

        private HttpResponse<String> send(HttpRequest.Builder request, String bearer) throws Exception {
            if (bearer != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode situationEnvelope() throws Exception {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/situation"))
                    .timeout(Duration.ofSeconds(10))
                    .GET(), admin);
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        /** 收尾：按场次与想定精确回收本代写下的行（无外键，顺序无关）。 */
        private void closeAndClean() {
            try {
                if (exerciseId != null) {
                    for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                            "t_exercise_command", "t_exercise_log")) {
                        jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                                .param("id", exerciseId).update();
                    }
                    jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                        .param("planId", planId.toString()).update();
                jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                        .param("planId", planId.toString()).update();
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
