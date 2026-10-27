package com.dualx;

import com.dualx.api.ws.SituationSessionRegistry;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 态势 WS <b>保活与泄漏</b>的端到端验收，即设计
 * [02-push-ports](../../docs/design/02-push-ports.md) §7 的<b>判据 5</b>。
 *
 * <p><b>为什么单独一类</b>：空闲断开由会话表的 {@code @Scheduled} 空闲扫描驱动，而扫描周期（5s）
 * 与阈值（契约 30s）在别的类里都够不着开关。本类在自己的环境里把阈值覆写成 1.5s，语义
 * （「无上行即断开」）一字未改，只是把等待压到用例能承受的量级——真正的 30s 只能现场验收
 * （设计 05 §7），本类不假装覆盖它。</p>
 *
 * <p><b>成对的两个用例</b>：只证明「不放上行就会被断开」是不够的——那用一个「关掉一切」的实现也能通过。
 * 故另一条用例证明反面：<b>持续上行（{@code PING}）的会话跨过扫描拍也不会被断开</b>，且能拿回
 * {@code PONG} 应答。两条一起才说明服务端判的是「距上次上行」而不是「距上次推送」。</p>
 *
 * <p><b>为什么用会话表的计数做判据</b>：判据 5 的第二半是「断开后 registry 中该会话已注销，活跃会话数
 * 回到真实值」。{@code activeSessionCount()} 正是这条观测的对外读数，比从外部探测「还有没有推送」
 * 更直接，也不受「此刻恰好有没有变化」干扰。</p>
 *
 * <p>不写成 {@code @Transactional}：WS 生命周期是进程内运行态、不落库，故本类不做任何数据库清理，
 * 也不需要想定/编成夹具（保活与演习无关）。</p>
 */
class PushKeepAliveIT {

    /**
     * 覆写契约值 {@code scds.push.idle-timeout-ms}（30000）。它只决定「多久算空闲」这一个判断的阈值，
     * 调短不改变任何分支。
     */
    private static final long IDLE_TIMEOUT_MS = 1500L;

    /** 契约 {@code clientToServer} 的保活动作，逐字取自 {@code paths/situation.yaml}。 */
    private static final String PING = "{\"action\":\"PING\"}";

    /**
     * 空闲扫描一拍的长度（{@code SituationSessionRegistry.IDLE_SCAN_INTERVAL_MS} 是包内常量，取不到）。
     * 断开的判定只在扫描拍上发生，故「跨过一拍还不断」是保活成立的最小证据。
     */
    private static final long SCAN_INTERVAL_MS = 5000L;

    private static App app;
    private static SituationSessionRegistry sessions;

    @BeforeAll
    static void bootSimulatedApplication() throws Exception {
        app = App.boot(ApplicationStartupSupport.newKeyPair());
        sessions = app.context.getBean(SituationSessionRegistry.class);
    }

    @AfterAll
    static void shutdownApplication() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("保活（判据 5）：连上后不发任何上行 → 服务端按空闲阈值主动断开（1000 IDLE_TIMEOUT），会话随即注销、活跃数回落")
    void idleSessionIsClosedByServerAndUnregistered() throws Exception {
        long baseline = sessions.activeSessionCount();
        FrameCollector collector = new FrameCollector(app.mapper);
        try (Ws ws = Ws.open(app.wsUrl("/ws/v1/situation"), app.admin, collector)) {
            collector.awaitFrame(JsonNode::isObject, Duration.ofSeconds(5));
            await("会话已登记", () -> sessions.activeSessionCount() == baseline + 1);

            // 一条上行都不发：扫描 5s 一拍、阈值 1.5s，故关闭必然落在 20s 内。
            CloseStatus status = collector.awaitClose(Duration.ofSeconds(20));
            assertThat(status.getCode()).as("空闲断开不新造关闭码，用 RFC 6455 的正常关闭").isEqualTo(1000);
            assertThat(status.getReason()).as("reason 逐字表达断开原因").isEqualTo("IDLE_TIMEOUT");

            await("服务端已注销该会话、活跃数回到真实值", () -> sessions.activeSessionCount() == baseline);
            assertThat(ws.session().isOpen()).as("连接已被服务端关掉").isFalse();
        }
    }

    @Test
    @DisplayName("保活的反面：持续上行（PING）的会话跨过扫描拍不被断开，且收到 PONG 应答")
    void pingingSessionStaysConnectedAndGetsPong() throws Exception {
        long baseline = sessions.activeSessionCount();
        FrameCollector collector = new FrameCollector(app.mapper);
        AtomicBoolean pinging = new AtomicBoolean(true);
        try (Ws ws = Ws.open(app.wsUrl("/ws/v1/situation"), app.admin, collector)) {
            collector.awaitFrame(JsonNode::isObject, Duration.ofSeconds(5));
            Thread ping = Thread.ofVirtual().name("keepalive-ping").start(() -> pingUntilStopped(ws, pinging));
            try {
                collector.awaitFrame(node -> "PONG".equals(node.path("action").asText()), Duration.ofSeconds(5));
                // 跨过至少一个扫描拍：若保活被误判成「无下行即断开」，这里会先收到关闭帧。
                sleep(SCAN_INTERVAL_MS + 1000L);
                assertThat(collector.closed()).as("有上行的会话不得被空闲断开").isFalse();
                assertThat(sessions.activeSessionCount()).as("会话仍在表中").isEqualTo(baseline + 1);
            } finally {
                pinging.set(false);
                ping.join(TimeUnit.SECONDS.toMillis(3));
            }
        }
        await("本地关闭后会话注销", () -> sessions.activeSessionCount() == baseline);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 每 300ms 发一条 PING，直到被叫停；发送失败即退出（会话已断，没有继续上行的意义）。 */
    private static void pingUntilStopped(Ws ws, AtomicBoolean pinging) {
        while (pinging.get()) {
            try {
                ws.session().sendMessage(new TextMessage(PING));
            } catch (Exception e) {
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 轮询到条件成立或超时：断开与注销都是异步的，断言前必须等。 */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("等待超时（20s）：" + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    /** 收集服务端推送的每一帧与关闭帧，支持按谓词等待（虚拟线程与网络都有延迟，不能用固定 sleep）。 */
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

        /** 服务端主动关闭时凭这里的关闭码与 reason 判定断开原因。 */
        CloseStatus awaitClose(Duration timeout) throws Exception {
            CloseStatus status = closes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (status == null) {
                throw new AssertionError("等待服务端关闭帧超时");
            }
            return status;
        }

        /** 是否已经收到过关闭帧（不断言，只做「不该断」的检查）。 */
        boolean closed() {
            return !closes.isEmpty();
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

    /** 一次 WS 会话；既用于结束时关干净，也用于在保活用例里主动发上行。 */
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

        WebSocketSession session() {
            return session;
        }

        @Override
        public void close() throws Exception {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
    }

    /** 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP/WS，以及仿真网关。 */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String authority;
        private final String admin;

        private App(ConfigurableEnvironment environment, KeyPair key) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.authority = "127.0.0.1:" + port;
            this.admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
        }

        private static App boot(KeyPair key) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            environment.getPropertySources().addFirst(new MapPropertySource("push-keep-alive-it", Map.of(
                    "scds.gateway.mode", "simulator",
                    "scds.push.idle-timeout-ms", Long.toString(IDLE_TIMEOUT_MS))));
            return new App(environment, key);
        }

        private String wsUrl(String path) {
            return "ws://" + authority + path;
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
