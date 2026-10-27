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
import org.junit.jupiter.api.AfterAll;
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
import java.util.ArrayList;
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
 * 态势推送在<b>换场与补齐</b>两条路径上的端到端验收，即设计
 * [02-push-ports](../../docs/design/02-push-ports.md) §7 的<b>判据 6</b>（演习切换）与<b>判据 2</b>（补齐正确）。
 *
 * <p><b>为什么单独一类</b>：这两条判据都要真开一场演习才有意义，而那意味着往库里写想定、编成与场次。
 * {@code PushIsomorphismIT} 的定位是「不落库、只比形状」，把生命周期夹具塞进去会同时破坏它的注释与
 * 清理策略，故另立本类，自带按 id 精确回收。</p>
 *
 * <p><b>本类比两个既有类多证明了什么</b>：{@code SituationStateIT} 证明状态机的读侧，
 * {@code PushIsomorphismIT} 证明推送与 Poll 同构，但两者都没有把「HTTP 开演/停演 → 生产路径上的
 * {@code SituationSessionBinder} → 已连上的 WS 客户端收到什么」这一整条串起来。本类用
 * <b>真 HTTP 开演/停演</b>（不是直接调 binder）驱动，因此同时覆盖了 M3 LIFE 与 M4 推送的接缝。</p>
 *
 * <p><b>帧字节取自协议黄金样本</b>，不自造（自算校验和等于自证）。仿真网关与真链路共用同一个
 * {@code LinkRegistry} 与同一个 {@code HardwareEventSink}，故「变化 → 推增量」这条链是同一条。</p>
 *
 * <p><b>刻意不覆盖跨代恢复（判据 9）</b>：那条判据要求同一 JVM 内先后起两个完整进程（两个 epoch），
 * 其机制是「新进程把窗口下界抬到旧 N 之上 → 旧 N 判超窗 → close 4000 → 改全量」，属于
 * 判据 3 的同一套出口加上 epoch 比对，留待单独一段实现，本类不假装覆盖。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。</p>
 */
class PushSituationRecoveryIT {

    // ── 协议黄金样本（与 SituationStateIT 同源，未复算） ─────────────────────────

    /** {@code ff03} 短版心跳：马甲 2 / 弹药 30/30 / HP 100 → 离线转 LIVE。 */
    private static final String HEARTBEAT_VEST2_LIVE = "ff03000002001e64001ea4affa";

    /** {@code ff04} 室外 GPS：马甲 2 → 第二次单元变化（定位）。 */
    private static final String GPS_VEST2 = "ff0400000207136d12017e8822c7affa";

    /** {@code ff03} 短版心跳：马甲 2 / HP 0 → 第三次单元变化（阵亡冻结）。 */
    private static final String HEARTBEAT_VEST2_DIE = "ff03000002000000000004affa";

    /** 本场编成：与黄金样本的编号对齐（2 是本类唯一驱动的马甲，其余用于凑出「装载了 N 个单元」）。 */
    private static final List<RosterRow> ROSTER = List.of(
            new RosterRow(1, "红军-01", Team.RED),
            new RosterRow(2, "红军-02", Team.RED),
            new RosterRow(4, "蓝军-04", Team.BLUE),
            new RosterRow(5, "蓝军-05", Team.BLUE));

    private static final UUID PLAN_ID = UuidV7.generate();
    private static final String PORT_NAME = "COM7";

    private static App app;
    private static SimulatedLinkAdapter links;
    private static JdbcClient jdbc;
    private static SituationQuery query;

    private static final List<String> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootSeedAndOpenLink() throws Exception {
        app = App.boot(ApplicationStartupSupport.newKeyPair());
        links = (SimulatedLinkAdapter) app.context.getBean(DeviceLinkAdmin.class);
        jdbc = app.context.getBean(JdbcClient.class);
        query = app.context.getBean(SituationQuery.class);
        seedPlanAndRoster();
        // 投喂上行帧要求链路已开（仿真下不开链路投喂是非法用法）。
        links.openSerial(new LinkConfig(LinkKind.SERIAL_VEST, PORT_NAME, null,
                false, null, null, false, null, null, null));
    }

    @AfterAll
    static void cleanUp() {
        if (app != null) {
            for (String exerciseId : createdExercises) {
                // 无外键，顺序无关；逐表按场次精确回收，不碰其他用例的数据。
                for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                        "t_exercise_command", "t_exercise_log")) {
                    jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                            .param("id", exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
            }
            jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            app.close();
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("演习切换（判据 6）：开演推 full=true/running=true 并装载编成；停演推 full=true/running=false/units 空")
    void exerciseSwitchPushesAuthoritativeFullFrames() throws Exception {
        stopIfRunning();
        FrameCollector collector = new FrameCollector(app.mapper);
        try (Ws ignored = Ws.open(app.wsUrl("/ws/v1/situation"), app.admin, collector)) {
            JsonNode idle = collector.awaitFrame(
                    node -> node.path("full").asBoolean() && !node.path("running").asBoolean(),
                    Duration.ofSeconds(5));
            assertThat(idle.path("units")).as("无演习时战场为空").isEmpty();

            String exerciseId = startExercise();
            JsonNode started = collector.awaitFrame(
                    node -> node.path("full").asBoolean() && node.path("running").asBoolean(),
                    Duration.ofSeconds(5));
            assertThat(started.path("exerciseId").asText()).isEqualTo(exerciseId);
            assertThat(started.path("units")).as("换场用全量帧表达「战场重建」").hasSize(ROSTER.size());
            assertThat(started.path("revision").asLong())
                    .as("切换本身也 +1，只增不减").isGreaterThan(idle.path("revision").asLong());

            stopExercise();
            JsonNode stopped = collector.awaitFrame(
                    node -> node.path("full").asBoolean() && !node.path("running").asBoolean(),
                    Duration.ofSeconds(5));
            assertThat(stopped.path("exerciseId").isNull()).as("停演后不再自报场次").isTrue();
            assertThat(stopped.path("units")).as("停演后的第一帧必须能清空战场").isEmpty();
            assertThat(stopped.path("revision").asLong()).isGreaterThan(started.path("revision").asLong());
        }
    }

    @Test
    @DisplayName("补齐逐帧（判据 2）：持「当前 revision − 3」握手，收到 3 帧 full=false、revision 连续无跳号，末帧与 Poll 全量一致")
    void backlogIsDeliveredFrameByFrameWithConsecutiveRevisions() throws Exception {
        stopIfRunning();
        startExercise();
        try {
            long switchRevision = query.revision();

            // 三次单元变化，各占一个 revision（装载后的单元一律 stamped 切换点，故首个变化即 +1）
            links.injectSerial(HEARTBEAT_VEST2_LIVE);
            await("马甲 2 转 LIVE 并推高一个 revision", () -> query.revision() == switchRevision + 1);
            links.injectSerial(GPS_VEST2);
            await("马甲 2 拿到坐标并推高一个 revision", () -> query.revision() == switchRevision + 2);
            links.injectSerial(HEARTBEAT_VEST2_DIE);
            await("马甲 2 转 DIE 并推高一个 revision", () -> query.revision() == switchRevision + 3);

            FrameCollector collector = new FrameCollector(app.mapper);
            try (Ws ignored = Ws.open(
                    app.wsUrl("/ws/v1/situation?sinceRevision=" + switchRevision), app.admin, collector)) {
                JsonNode first = collector.awaitFrame(node -> !node.path("full").asBoolean(), Duration.ofSeconds(5));
                JsonNode second = collector.awaitFrame(node -> !node.path("full").asBoolean(), Duration.ofSeconds(5));
                JsonNode third = collector.awaitFrame(node -> !node.path("full").asBoolean(), Duration.ofSeconds(5));

                assertThat(List.of(revisionOf(first), revisionOf(second), revisionOf(third)))
                        .as("补齐帧逐 revision 推进，连续无跳号")
                        .containsExactly(switchRevision + 1, switchRevision + 2, switchRevision + 3);
                assertThat(first.path("changes")).as("增量帧只带本次变化的那一个单元").hasSize(1);
                assertThat(third.path("changes").get(0).path("vestNum").asInt()).isEqualTo(2);
                assertThat(third.path("changes").get(0).path("status").asText())
                        .as("从切换点补齐到的是此刻的权威状态").isEqualTo("DIE");

                assertThat(third.path("revision").asLong()).as("末帧 revision 与全量帧一致")
                        .isEqualTo(app.situationEnvelope().path("revision").asLong());
            }
        } finally {
            stopExercise();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 走真 HTTP 开演（真 LIFE → 真 binder → 真状态机），而不是直接调 binder。 */
    private static String startExercise() throws Exception {
        HttpResponse<String> response = app.post("/api/v1/exercise/current",
                app.mapper.createObjectNode().put("planId", PLAN_ID.toString()), app.admin);
        assertThat(response.statusCode()).isEqualTo(201);
        String exerciseId = app.mapper.readTree(response.body()).path("exerciseId").asText();
        createdExercises.add(exerciseId);
        return exerciseId;
    }

    private static void stopExercise() throws Exception {
        assertThat(app.delete("/api/v1/exercise/current", app.admin).statusCode()).isEqualTo(200);
    }

    /** 用例开头的清场：有演习就收尾（200），否则契约明确是 409——两种都不是失败。 */
    private static void stopIfRunning() throws Exception {
        assertThat(app.delete("/api/v1/exercise/current", app.admin).statusCode()).isIn(200, 409);
    }

    private static void seedPlanAndRoster() {
        jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                        + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                .param("id", PLAN_ID.toString())
                .param("name", "推送恢复IT想定-" + PLAN_ID).update();
        for (RosterRow row : ROSTER) {
            jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                            + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                    .param("planId", PLAN_ID.toString())
                    .param("vestNum", row.vestNum()).param("name", row.name())
                    .param("team", row.team().name()).update();
        }
    }

    private static long revisionOf(JsonNode envelope) {
        return envelope.path("revision").asLong();
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

    /** 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP/WS，以及仿真网关。 */
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
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.authority = "127.0.0.1:" + port;
            this.base = "http://" + authority;
            this.admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }

        private static App boot(KeyPair key) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "push-situation-recovery-it", Map.of("scds.gateway.mode", "simulator")));
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
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode situationEnvelope() throws Exception {
            HttpResponse<String> response = get("/api/v1/situation", admin);
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
