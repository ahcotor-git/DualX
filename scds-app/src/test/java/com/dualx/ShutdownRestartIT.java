package com.dualx;

import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.gateway.link.RealDeviceLinkAdmin;
import com.dualx.protocol.ProtocolConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 停机（T-STOP / {@code INV-STOP}）的端到端验收：<b>HTTP、WS、串口、TCP、队列各自释放，并能立即重启</b>。
 *
 * <p><b>与既有用例的分工</b>：{@code ApplicationStartupIT} 只证明「上下文关闭后 HTTP 端口不再被服务」；
 * {@code HwLinkHttpIT} 只在**显式 {@code DELETE /devices/links/uwb}** 后证明 12001 被释放；
 * {@code PushKeepAliveIT} 覆盖 WS 的保活断开。三者都不覆盖本条——<b>进程停机这一动作本身</b>是否把
 * 全部资源交还。</p>
 *
 * <p><b>为什么必须真机端口</b>：硬约束端口（HTTP 11001 / UWB TCP 12001）是本条判据唯一的
 * 「同一端口」证据——端口 0 每次分配新号，第二次绑定成功什么也证明不了。12001 是硬约束里唯一的
 * 固定监听口，故本条以它为准；被占用时按 {@code HwLinkHttpIT} 的先例以 {@code Assumptions} 跳过，
 * 而不是换端口重试（「换个端口也能跑」正是硬约束要禁掉的行为）。</p>
 *
 * <p><b>线程判据而非仅端口</b>：Netty 事件循环与态势写手都是守护线程，端口没放掉时进程照样能退出，
 * 于是「没有报错」不等于「资源已还」。本类同时断言这些平台线程在停机后消失——端口是现象，线程是根因。</p>
 *
 * <p><b>WS 判据的口径</b>：设计 02 §4.8 裁定停机<b>不广播</b>任何「我要关了」帧，直接关连接，
 * 客户端凭「断线即重连 + 低频 Poll」恢复。故本类断言的是「会话被服务端断开」，而不是「收到关闭帧」。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走应用自己的线程与连接。本类只读不写业务表，
 * 故无需按编号回收数据；两个实例都以 {@code server.port=0} 启动，也不占用硬约束的 HTTP 端口。</p>
 */
class ShutdownRestartIT {

    @Test
    @DisplayName("T-STOP 停机：WS 会话断开、12001 与态势写手/Netty 线程全部退出；随后可在同一硬约束端口立即重启")
    void shutdownReleasesEverythingAndImmediateRestartBindsSameHardPort() throws Exception {
        Assumptions.assumeTrue(portFree(ProtocolConstants.UWB_TCP_SERVER_PORT),
                "本机 " + ProtocolConstants.UWB_TCP_SERVER_PORT + " 已被占用：契约禁止换端口，"
                        + "跳过真实监听（释放端口后可重跑）");

        // 不覆盖 scds.gateway.mode → 走 application.yml 的 real 默认值，12001 才真的被监听。
        App first = App.boot(ApplicationStartupSupport.newKeyPair(), null);
        RecordingLinksHandler handler = new RecordingLinksHandler();
        HikariDataSource pool = first.context.getBean(HikariDataSource.class);
        int httpPort = first.port();
        WebSocketSession linksWs = null;
        try {
            assertThat(first.context.getBean(DeviceLinkAdmin.class))
                    .as("real 模式装配的是真链路，否则 12001 的释放无从谈起")
                    .isInstanceOf(RealDeviceLinkAdmin.class);

            // ── 停机前：12001 真监听、链路状态 WS 真在跑、态势写手真在跑
            assertThat(first.openUwbTcpServer().statusCode()).isEqualTo(200);
            assertThat(portFree(ProtocolConstants.UWB_TCP_SERVER_PORT))
                    .as("已开链路时 %s 必须真的被本进程占用", ProtocolConstants.UWB_TCP_SERVER_PORT)
                    .isFalse();

            // 会话必须一直开着：客户端自己 close 也会置位 closed，就不能证明「是停机断的」。
            linksWs = first.openLinksWs(handler);
            await("链路状态 WS 首帧快照", () -> handler.frames.size() >= 1);
            assertThat(handler.closed).as("先确认关闭前会话是活的，否则后面的「已断开」不成立").isFalse();

            assertThat(platformThreadsNamed("uwb-boss")).as("Netty 事件循环在位").isNotEmpty();
            assertThat(platformThreadsNamed("scds-state-writer")).as("态势写手线程在位").isNotEmpty();
            assertThat(portFree(httpPort)).as("HTTP 端口正在服务").isFalse();

            // ── 停机（这一动作本身即被测对象）
            first.close();

            // ── 停机后：WS 会话被服务端直接断开（不广播关帧，设计 02 §4.8）
            await("WS 会话被停机断开", () -> handler.closed);
            // ── 硬约束端口交还：这是「立即重启」的前提
            await("12001 释放", () -> portFree(ProtocolConstants.UWB_TCP_SERVER_PORT));
            // ── HTTP 端口不再被服务
            await("HTTP 端口释放", () -> portRefuses(httpPort));
            // ── 队列与链路线程退出：端口是现象，线程是根因
            await("态势写手线程退出", () -> platformThreadsNamed("scds-state-writer").isEmpty());
            await("Netty boss 事件循环退出", () -> platformThreadsNamed("uwb-boss").isEmpty());
            await("Netty worker 事件循环退出", () -> platformThreadsNamed("uwb-worker").isEmpty());
            assertThat(pool.isClosed()).as("停机后连接池必须关闭").isTrue();
        } finally {
            if (linksWs != null && linksWs.isOpen()) {
                try {
                    linksWs.close();
                } catch (IOException ignored) {
                    // 已被停机断开；兜底关闭只为不泄漏客户端资源。
                }
            }
            first.close();
        }

        // ── 立即重启：同一硬约束端口必须能重新绑定，且 HTTP 立刻可用
        try (App second = App.boot(ApplicationStartupSupport.newKeyPair(), null)) {
            assertThat(second.health().statusCode()).isEqualTo(200);
            HttpResponse<String> reopened = second.openUwbTcpServer();
            assertThat(reopened.statusCode())
                    .as("立即重启后必须能重新绑定硬约束端口 %s", ProtocolConstants.UWB_TCP_SERVER_PORT)
                    .isEqualTo(200);
            assertThat(second.mapper.readTree(reopened.body()).path("state").asText()).isEqualTo("OPEN");
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 平台线程里名字以 {@code prefix} 开头的那些。虚拟线程不进 {@link Thread#getAllStackTraces()}，故只覆盖平台线程。 */
    private static Set<String> platformThreadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.equals(prefix) || name.startsWith(prefix + "-"))
                .collect(Collectors.toSet());
    }

    /** 轮询到条件成立或超时：停机是异步收尾，断言前必须等它落定。 */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待超时（10s）：" + what);
    }

    /** 端口是否可绑定：既做前置探测，也做「停机后已释放」的判据。 */
    private static boolean portFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException busy) {
            return false;
        }
    }

    /** 端口是否已不再被服务（连不上即已释放）。 */
    private static boolean portRefuses(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return false;
        } catch (IOException refused) {
            return true;
        }
    }

    /** 链路状态 WS 的客户端侧记录：只关心「收到过帧」与「服务端把它断开了」。 */
    private static final class RecordingLinksHandler extends TextWebSocketHandler {

        private final BlockingQueue<String> frames = new ArrayBlockingQueue<>(64);
        private volatile boolean closed;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.offer(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }
    }

    /** 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP，以及装配出来的真/仿真链路。 */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String base;
        private final String admin;
        private final HttpClient http;

        private App(ConfigurableEnvironment environment, KeyPair key) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            this.base = "http://127.0.0.1:"
                    + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }

        /**
         * @param gatewayMode {@code simulator} / {@code real}；传 {@code null} 表示不覆盖，
         *                    用 {@code application.yml} 的默认值（{@code real}）
         */
        private static App boot(KeyPair key, String gatewayMode) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            if (gatewayMode != null) {
                environment.getPropertySources().addFirst(
                        new MapPropertySource("shutdown-restart-it", Map.of("scds.gateway.mode", gatewayMode)));
            }
            return new App(environment, key);
        }

        private int port() {
            return Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
        }

        /** 开 UWB TCP Server：跑在硬约束 12001 上（端口省略即取默认）。 */
        private HttpResponse<String> openUwbTcpServer() throws Exception {
            return post("/api/v1/devices/links/uwb",
                    mapper.createObjectNode().put("dataSource", "TCP_SERVER").put("host", "127.0.0.1"), admin);
        }

        private HttpResponse<String> health() throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        private WebSocketSession openLinksWs(RecordingLinksHandler handler) throws Exception {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + admin);
            return new StandardWebSocketClient()
                    .execute(handler, headers, URI.create("ws://127.0.0.1:" + port() + "/ws/v1/devices/links"))
                    .get(5, TimeUnit.SECONDS);
        }

        private HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
