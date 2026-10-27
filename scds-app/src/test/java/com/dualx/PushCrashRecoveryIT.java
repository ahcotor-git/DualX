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
import java.time.LocalDateTime;
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
 * 崩溃自愈的端到端验收，即设计 [02-push-ports](../../docs/design/02-push-ports.md) §7 的<b>判据 6</b> 中
 * 「进程被杀后的 {@code CRASH_RECOVERED} 收尾」那一支；正常停演那一支由
 * {@code PushSituationRecoveryIT} 覆盖。
 *
 * <p><b>判据原文</b>：「停演（含进程被杀后的 {@code CRASH_RECOVERED} 收尾）后，客户端收到的下一帧为
 * {@code full=true} 且 {@code running=false}、{@code units} 为空。」</p>
 *
 * <p><b>为什么不能复用 {@code PushSituationRecoveryIT}</b>：那一支走 HTTP {@code DELETE} 停演——路径上
 * LIFE 会先叫 {@code SituationSessionBinder.onExerciseStopping} 写终局、再 {@code freezeAndClose}，
 * 整条链都在<b>同一个</b>进程里，共享同一份内存态势。本支的关键恰恰是「<b>没有</b>人来停演」：场次由
 * <b>下一个进程</b>的启动自愈（{@code ExerciseLivenessRecover}）按 {@code CRASH_RECOVERED} 补写收尾，
 * 期间没有任何 binder 参与、也没有任何内存态可以接手。要验它，必须真的起两个进程代。</p>
 *
 * <p><b>「进程被杀」在这里怎么表达</b>：{@code ApplicationStartupSupport.start()} 起的是完整生产上下文；
 * 把第一代的上下文<b>关掉而不停演、不清库</b>，库里那一行就仍是 {@code ended_at IS NULL} 的僵尸，
 * 且它的 {@code owner_instance} 已随进程消失——这正是异常退出留下的现场。之所以不需要真
 * {@code kill -9}，是因为自愈规则本身<b>不区分</b>优雅与异常退出：设计 01 §4.1 明确规定「启动时发现
 * 『进行中』的行一律收尾，包括『就是本实例、心跳还很新』的快速重启」，判据只看库里那行是否仍
 * {@code ended_at IS NULL}（另经核对：{@code scds-core} 全模块没有任何 {@code @PreDestroy} /
 * {@code DisposableBean} / {@code SmartLifecycle} 会在停机路径上写 {@code ended_at}，故本类的简化
 * 不改变被验行为）。</p>
 *
 * <p><b>本类证明的三件事</b>：① 第一代留下的僵尸确实被第二代的自愈按 {@code CRASH_RECOVERED} 收尾；
 * ② 客户端在第二代连上后<b>下一帧</b>就是 {@code full=true} 且 {@code running=false}、{@code units}
 * 为空，并与 HTTP Poll 同构——上一个进程的战场残影不会被带过来；③ 自愈不只是清了一行：唯一索引不再
 * 被僵尸占住，新演习能开起来，且 revision 在同一代内<b>继续向上不回退</b>（设计 02 §4.4）。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。</p>
 */
class PushCrashRecoveryIT {

    // ── 协议黄金样本（与 SituationStateIT 同源，未复算） ─────────────────────────

    /** {@code ff03} 短版心跳：马甲 2 / 弹药 30/30 / HP 100 → 离线转 LIVE。 */
    private static final String HEARTBEAT_VEST2_LIVE = "ff03000002001e64001ea4affa";

    /** {@code ff04} 室外 GPS：马甲 2 → 定位变化。重复投喂每次稳定推高一位 revision（阵亡才冻结坐标）。 */
    private static final String GPS_VEST2 = "ff0400000207136d12017e8822c7affa";

    /** 编成：2 是唯一被驱动的马甲，其余用于凑出「战场上有 N 个单元」——「战场清空」才不是空断言。 */
    private static final List<RosterRow> ROSTER = List.of(
            new RosterRow(1, "红军-01", Team.RED),
            new RosterRow(2, "红军-02", Team.RED),
            new RosterRow(4, "蓝军-04", Team.BLUE),
            new RosterRow(5, "蓝军-05", Team.BLUE));

    private static final String PORT_NAME = "COM7";

    private static KeyPair keyPair;
    private static String admin;

    @BeforeAll
    static void keyAndToken() throws Exception {
        keyPair = ApplicationStartupSupport.newKeyPair();
        admin = ApplicationStartupSupport.externalAccessToken(keyPair, List.of("ADMIN"));
    }

    @Test
    @DisplayName("崩溃自愈（判据 6）：僵尸场次被 CRASH_RECOVERED 收尾——重启后客户端下一帧 full=true/running=false/units 空，新演习照样能开且 revision 继续递增")
    void restartAfterCrashPushesCleanFullFrameAndKeepsRevisionMonotonic() throws Exception {
        // ── 第一代进程：开演并留下一个有内容的战场，然后「被杀」 ──────────────────────
        App crashed = App.boot(keyPair);
        String crashedEpoch;
        long crashedRevision;
        try {
            crashed.openSerialLink();
            crashed.startExercise();
            crashed.injectAndAwaitBump(HEARTBEAT_VEST2_LIVE);
            crashed.injectAndAwaitBump(GPS_VEST2);
            crashedEpoch = crashed.query.epoch();
            crashedRevision = crashed.query.revision();

            JsonNode battlefield = crashed.situationEnvelope();
            assertThat(battlefield.path("running").asBoolean()).as("第一代确实在跑一场演习").isTrue();
            assertThat(battlefield.path("units"))
                    .as("战场上有编成，故后面「units 为空」不是空断言").hasSize(ROSTER.size());
        } finally {
            crashed.crash();
        }
        assertThat(crashedRevision).as("第一代留下了非零 revision").isGreaterThan(0);

        // ── 第二代进程：启动自愈必须把上一代留下的僵尸场次收尾 ─────────────────────
        App next = App.boot(keyPair);
        try {
            assertThat(next.query.epoch()).as("换代必换代标识，否则「重启」无从辨认").isNotEqualTo(crashedEpoch);

            // 取证一：库里那一行确实被自愈补写收尾——这是「异常中断」与「正常停演」唯一的区别所在。
            assertThat(endReasonOf(next, crashed.exerciseId))
                    .as("只由启动自愈补写；走 HTTP 停演恒为 NORMAL（契约 exercise.yaml）")
                    .isEqualTo("CRASH_RECOVERED");
            assertThat(endedAtOf(next, crashed.exerciseId))
                    .as("僵尸场次已被收尾，唯一索引 uk_exercise_running 不再被占住")
                    .isNotNull();

            // 取证二：客户端连上第二代后，下一帧就是干净全量。
            FrameCollector collector = new FrameCollector(next.mapper);
            try (Ws ignored = Ws.open(next.wsUrl("/ws/v1/situation"), admin, collector)) {
                JsonNode first = collector.awaitFrame(PushCrashRecoveryIT::isEnvelope, Duration.ofSeconds(5));
                assertThat(first.path("full").asBoolean())
                        .as("重启后客户端拿到的第一帧必须是全量，增量无从补齐陌生基线").isTrue();
                assertThat(first.path("epoch").asText()).as("每一帧都自报本代标识").isEqualTo(next.query.epoch());
                assertThat(first.path("running").asBoolean())
                        .as("判据 6：僵尸场次已被自愈收尾，战场不存在").isFalse();
                assertThat(first.path("units")).as("判据 6：units 为空").isEmpty();
                assertThat(first.path("exerciseId").isNull()).as("不再自报已被收尾的场次").isTrue();
                long idleRevision = first.path("revision").asLong();

                // 与 Poll 同构：HTTP 读侧看到的是同一个干净状态（同一 Java 类型、同一状态源）。
                JsonNode polled = next.situationEnvelope();
                assertThat(polled.path("running").asBoolean()).isFalse();
                assertThat(polled.path("units")).isEmpty();
                assertThat(polled.path("revision").asLong())
                        .as("WS 首帧与 Poll 全量同构（判据 1 的口径在换场后的延续）")
                        .isEqualTo(idleRevision);

                // 取证三：自愈的价值在于场次能接着开——唯一索引没被僵尸占住，且 revision 只增不减。
                String newExerciseId = next.startExercise();
                JsonNode started = collector.awaitFrame(
                        node -> node.path("full").asBoolean() && node.path("running").asBoolean(),
                        Duration.ofSeconds(5));
                assertThat(started.path("exerciseId").asText()).isEqualTo(newExerciseId);
                assertThat(started.path("units")).as("新演习装载了完整编成").hasSize(ROSTER.size());
                assertThat(started.path("revision").asLong())
                        .as("场次切换同样只增不减（设计 02 §4.4）：同代内 revision 继续向上，不回退")
                        .isGreaterThan(idleRevision);
            }
        } finally {
            // 两代的行都要收：第一代没走停演，它的行只能在这里清（第二代的自愈只补了结束时间）。
            next.purge(crashed, next);
            next.close();
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 直接读列：领域 {@code Exercise} 尚未映射 {@code end_reason}（与 {@code ExerciseLivenessRecoverIT} 同法）。 */
    private static String endReasonOf(App app, String exerciseId) {
        return app.jdbc.sql("SELECT end_reason FROM t_exercise WHERE id = :id")
                .param("id", exerciseId).query(String.class).optional().orElse(null);
    }

    private static LocalDateTime endedAtOf(App app, String exerciseId) {
        return app.jdbc.sql("SELECT ended_at FROM t_exercise WHERE id = :id")
                .param("id", exerciseId).query(LocalDateTime.class).optional().orElse(null);
    }

    /** 是不是态势信封（控制帧如 PONG 没有 revision）。 */
    private static boolean isEnvelope(JsonNode node) {
        return node.has("revision");
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
     * <p>每代自带一份想定与编成（{@link #planId} 逐代随机），故两代之间不共享夹具行。</p>
     */
    private static final class App {

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
                    "push-crash-recovery-it", Map.of("scds.gateway.mode", "simulator")));
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
        private String startExercise() throws Exception {
            // 想定与编成必须先落库：开演入口是从 t_plan / t_plan_roster 读编成的。
            seedPlanAndRoster();
            HttpResponse<String> response = post("/api/v1/exercise/current",
                    mapper.createObjectNode().put("planId", planId.toString()));
            assertThat(response.statusCode()).isEqualTo(201);
            exerciseId = mapper.readTree(response.body()).path("exerciseId").asText();
            return exerciseId;
        }

        private void seedPlanAndRoster() {
            jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                            + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                    .param("id", planId.toString())
                    .param("name", "崩溃自愈IT想定-" + planId).update();
            for (RosterRow row : ROSTER) {
                jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                                + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                        .param("planId", planId.toString())
                        .param("vestNum", row.vestNum()).param("name", row.name())
                        .param("team", row.team().name()).update();
            }
        }

        private String wsUrl(String path) {
            return "ws://" + authority + path;
        }

        private HttpResponse<String> post(String path, JsonNode body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private JsonNode situationEnvelope() throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/situation"))
                    .timeout(Duration.ofSeconds(10))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        /**
         * 「进程异常退出」：关掉上下文，但<b>不</b>停演、<b>不</b>回收任何行。
         *
         * <p>库里那一行就这样留着 {@code ended_at IS NULL}，交给下一代的自愈按
         * {@code CRASH_RECOVERED} 收尾——这正是异常退出后的现场。</p>
         */
        private void crash() {
            shutdown();
        }

        /** 用例收尾：与 {@link #crash()} 是同一个动作，分开命名只为让调用处读起来不歧义。 */
        private void close() {
            shutdown();
        }

        private void shutdown() {
            http.close();
            context.close();
        }

        /** 收尾：按场次与想定精确回收<b>所有</b>传进来的代（无外键，顺序无关）。 */
        private void purge(App... apps) {
            for (App app : apps) {
                if (app.exerciseId != null) {
                    for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                            "t_exercise_command", "t_exercise_log")) {
                        jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                                .param("id", app.exerciseId).update();
                    }
                    jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", app.exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                        .param("planId", app.planId.toString()).update();
                jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                        .param("planId", app.planId.toString()).update();
            }
        }
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
}
