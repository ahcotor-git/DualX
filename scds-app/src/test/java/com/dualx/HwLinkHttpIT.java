package com.dualx;

import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.gateway.link.RealDeviceLinkAdmin;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import com.dualx.gateway.uwb.UwbSerialLink;
import com.dualx.protocol.Hex;
import com.dualx.protocol.ProtocolConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 硬件链路（HW-LINK，D06/D10）的端到端 HTTP 验收，即设计 05 §5 的 <b>H7</b>。
 *
 * <p>走完整生产入口——真 MySQL、真安全链、真 HTTP——把 {@code DeviceController} 的 6 条链路端点
 * 接到<b>真实装配出来的</b> {@link DeviceLinkAdmin} 上，验证「控制器 → 领域端口 → 网关链路」
 * 这条链真的通，而不只是两侧各自单测通过。链路规则（重开 409、UWB 互斥、串口独占、端口硬约束）
 * 刻意只在网关判一次（见 {@code DeviceController} 类注释），本类验证的就是那份口径能穿过 HTTP 与安全链。</p>
 *
 * <p><b>两个上下文，各证明一件事</b></p>
 * <ul>
 *   <li>{@code scds.gateway.mode=simulator}：全部链路语义在无硬件下确定性可验（恒三条快照、
 *       重开 409、UWB 两模式互斥、端口 400、同一串口不可被两条链路持有、关闭幂等与计数归零）。
 *       仿真<b>不</b>得写成现场通过（设计 05 §7）。</li>
 *   <li>{@code mode=real}（不覆盖，走 {@code application.yml} 默认值）：UWB TCP Server 在硬约束端口
 *       {@link ProtocolConstants#UWB_TCP_SERVER_PORT} 上<b>真实监听</b>，回环客户端发两帧粘连的黄金样本，
 *       HTTP 快照里 {@code framesTotal=2}、{@code fdCount=1}，关闭后端口真的释放。
 *       这一段是 {@code UwbLinkTest} 刻意留给 H7 的——它自己只用临时端口，不占 12001。</li>
 * </ul>
 *
 * <p><b>仿真为什么能替掉串口</b>：本机没有 COM7，真串口打开必然失败；{@code SimulatedLinkAdapter}
 * 不起任何线程、不碰物理端口，却与真链路共用同一个 {@code LinkRegistry}，因此状态机与冲突判据
 * 完全同源。真实串口的 115200 时序与马甲固件对拍只能现场验收。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。链路状态是进程内
 * 运行态、不落库，故本类不做任何数据库清理。</p>
 */
class HwLinkHttpIT {

    /** 黄金样本 {@code uwb41.samples[0]}/{@code [1]}：41B，帧头 AA 29、帧尾 55、校验自洽。 */
    private static final String UWB_FRAME_A =
            "aa29021300000000000000341200005613000000000000000000000000000000000000000000009755";
    private static final String UWB_FRAME_B =
            "aa29021100000000000000341200003c14000000000000000000000000000000000000000000007c55";

    /** 仿真模式下串口链路的默认波特率：请求不带 baudRate 时网关兜底（协议 A 口径）。 */
    private static final int VEST_BAUD_RATE = ProtocolConstants.SERIAL_BAUD_RATE;

    private static App simulator;

    @BeforeAll
    static void bootSimulator() throws Exception {
        simulator = App.boot(ApplicationStartupSupport.newKeyPair(), "simulator");
        // 装配层确实按 mode 二选一：这条断言成立的下一步，才是「其余用例验的是仿真链路语义」。
        assertThat(simulator.context.getBean(DeviceLinkAdmin.class)).isInstanceOf(SimulatedLinkAdapter.class);
    }

    @AfterAll
    static void shutdownSimulator() {
        if (simulator != null) {
            simulator.close();
        }
    }

    /** 每个用例都从「三条链路全 CLOSED」开始：链路状态是进程内单例，用例之间必须显式复位。 */
    @BeforeEach
    void resetLinks() throws Exception {
        simulator.delete("/api/v1/devices/links/serial", simulator.admin);
        simulator.delete("/api/v1/devices/links/uwb", simulator.admin);
    }

    @Test
    @DisplayName("链路快照恒三条且按声明序：未开即 CLOSED，客户端不必区分「缺席」与「关闭」")
    void linksSnapshotIsAlwaysThreeKinds() throws Exception {
        JsonNode links = simulator.linksSnapshot();
        assertThat(links.size()).isEqualTo(3);
        assertThat(links.get(0).path("kind").asText()).isEqualTo("SERIAL_VEST");
        assertThat(links.get(1).path("kind").asText()).isEqualTo("UWB_TCP_SERVER");
        assertThat(links.get(2).path("kind").asText()).isEqualTo("UWB_SERIAL");
        for (JsonNode link : links) {
            assertThat(link.path("state").asText()).isEqualTo("CLOSED");
            assertThat(link.path("target").isNull()).isTrue();
            assertThat(link.path("framesTotal").asLong()).isZero();
        }

        // 串口枚举已接线（仿真下没有真串口 → 空数组，非 501/500）
        HttpResponse<String> ports = simulator.get("/api/v1/devices/links/serial-ports", simulator.admin);
        assertThat(ports.statusCode()).isEqualTo(200);
        JsonNode portList = simulator.mapper.readTree(ports.body());
        assertThat(portList.isArray()).isTrue();
        assertThat(portList.size()).isZero();

        // 每马甲在线态仍归 STATE 域：本工作包不接线、不用链路计数冒充（设计 05 §5）
        simulator.assertProblem(simulator.get("/api/v1/devices/vests/1/link", simulator.admin),
                501, "NOT_IMPLEMENTED", "/api/v1/devices/vests/1/link");
    }

    @Test
    @DisplayName("马甲串口：开→200 OPEN；重开→409 不隐式重开；关闭幂等且计数归零")
    void serialOpenReopenIsConflictAndCloseIsIdempotent() throws Exception {
        HttpResponse<String> opened = simulator.post("/api/v1/devices/links/serial",
                simulator.serialRequest("COM7"), simulator.admin);
        assertThat(opened.statusCode()).isEqualTo(200);
        JsonNode status = simulator.mapper.readTree(opened.body());
        assertThat(status.path("kind").asText()).isEqualTo("SERIAL_VEST");
        assertThat(status.path("state").asText()).isEqualTo("OPEN");
        assertThat(status.path("target").asText()).isEqualTo("COM7");
        assertThat(status.path("baudRate").asInt()).isEqualTo(VEST_BAUD_RATE);
        assertThat(status.path("errorMessage").isNull()).isTrue();

        // 已开重开：契约要的是「客户端能区分已在开与断过重连」，所以是 409 而不是隐式重开
        simulator.assertProblem(simulator.post("/api/v1/devices/links/serial",
                        simulator.serialRequest("COM7"), simulator.admin),
                409, "DEVICE_LINK_ALREADY_OPEN", "/api/v1/devices/links/serial");

        HttpResponse<String> closed = simulator.delete("/api/v1/devices/links/serial", simulator.admin);
        assertThat(closed.statusCode()).isEqualTo(200);
        assertThat(simulator.mapper.readTree(closed.body()).path("state").asText()).isEqualTo("CLOSED");
        assertThat(simulator.linkOf("SERIAL_VEST").path("target").isNull()).isTrue();

        // 幂等：未打开时再关仍 200 且没有副作用
        assertThat(simulator.delete("/api/v1/devices/links/serial", simulator.admin).statusCode())
                .isEqualTo(200);
        assertThat(simulator.linkOf("SERIAL_VEST").path("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("UWB：端口硬约束 400 + 两模式互斥 409 + 关闭按当前模式分流")
    void uwbPortIsHardConstrainedAndModesAreMutuallyExclusive() throws Exception {
        // 端口硬约束：请求别的端口是 400，且不能真的去监听那个端口
        simulator.assertProblem(simulator.post("/api/v1/devices/links/uwb",
                        simulator.uwbTcpRequest("0.0.0.0", 12002), simulator.admin),
                400, "VALIDATION_FAILED", "/api/v1/devices/links/uwb");
        assertThat(simulator.linkOf("UWB_TCP_SERVER").path("state").asText()).isEqualTo("CLOSED");

        // SERIAL 缺 serialPortName：不在控制器复制这条校验，由网关折成 400（设计 05 §5 偏差①）
        simulator.assertProblem(simulator.post("/api/v1/devices/links/uwb",
                        simulator.uwbSerialRequest(null), simulator.admin),
                400, "VALIDATION_FAILED", "/api/v1/devices/links/uwb");

        // 端口省略即取硬约束 12001；host 省略即 0.0.0.0
        HttpResponse<String> tcp = simulator.post("/api/v1/devices/links/uwb",
                simulator.mapper.createObjectNode().put("dataSource", "TCP_SERVER"), simulator.admin);
        assertThat(tcp.statusCode()).isEqualTo(200);
        JsonNode tcpStatus = simulator.mapper.readTree(tcp.body());
        assertThat(tcpStatus.path("kind").asText()).isEqualTo("UWB_TCP_SERVER");
        assertThat(tcpStatus.path("state").asText()).isEqualTo("OPEN");
        assertThat(tcpStatus.path("target").asText())
                .isEqualTo("0.0.0.0:" + ProtocolConstants.UWB_TCP_SERVER_PORT);

        simulator.assertProblem(simulator.post("/api/v1/devices/links/uwb",
                        simulator.uwbTcpRequest("0.0.0.0", null), simulator.admin),
                409, "DEVICE_LINK_ALREADY_OPEN", "/api/v1/devices/links/uwb");
        // 互斥：另一种接入方式已开 → 同样 409，不隐式切换
        simulator.assertProblem(simulator.post("/api/v1/devices/links/uwb",
                        simulator.uwbSerialRequest("COM8"), simulator.admin),
                409, "DEVICE_LINK_ALREADY_OPEN", "/api/v1/devices/links/uwb");

        // 关闭端点不带 dataSource：按当前 active 的那条分流
        HttpResponse<String> closedTcp = simulator.delete("/api/v1/devices/links/uwb", simulator.admin);
        assertThat(closedTcp.statusCode()).isEqualTo(200);
        assertThat(simulator.mapper.readTree(closedTcp.body()).path("kind").asText()).isEqualTo("UWB_TCP_SERVER");
        assertThat(simulator.linkOf("UWB_TCP_SERVER").path("state").asText()).isEqualTo("CLOSED");
        assertThat(simulator.linkOf("UWB_TCP_SERVER").path("fdCount").isNull()).isTrue();

        // 换成 SERIAL：UWB 串口默认波特率 460800，与马甲串口的 115200 刻意不同
        HttpResponse<String> serial = simulator.post("/api/v1/devices/links/uwb",
                simulator.uwbSerialRequest("COM8"), simulator.admin);
        assertThat(serial.statusCode()).isEqualTo(200);
        JsonNode serialStatus = simulator.mapper.readTree(serial.body());
        assertThat(serialStatus.path("kind").asText()).isEqualTo("UWB_SERIAL");
        assertThat(serialStatus.path("target").asText()).isEqualTo("COM8");
        assertThat(serialStatus.path("baudRate").asInt()).isEqualTo(UwbSerialLink.DEFAULT_BAUD_RATE);

        // 分流证据：关闭返回的是 UWB_SERIAL 那条，而不是恒取 TCP
        HttpResponse<String> closedSerial = simulator.delete("/api/v1/devices/links/uwb", simulator.admin);
        assertThat(simulator.mapper.readTree(closedSerial.body()).path("kind").asText()).isEqualTo("UWB_SERIAL");
        assertThat(simulator.linkOf("UWB_SERIAL").path("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("同一物理串口不能被两条链路同时持有：409 DEVICE_LINK_SERIAL_BUSY")
    void sameSerialPortCannotBeHeldByTwoLinks() throws Exception {
        assertThat(simulator.post("/api/v1/devices/links/uwb",
                simulator.uwbSerialRequest("COM9"), simulator.admin).statusCode()).isEqualTo(200);

        simulator.assertProblem(simulator.post("/api/v1/devices/links/serial",
                        simulator.serialRequest("COM9"), simulator.admin),
                409, "DEVICE_LINK_SERIAL_BUSY", "/api/v1/devices/links/serial");

        // 释放后同一串口即可被别人持有——证明 409 来自「归属表占用」而不是残留状态
        assertThat(simulator.delete("/api/v1/devices/links/uwb", simulator.admin).statusCode()).isEqualTo(200);
        assertThat(simulator.post("/api/v1/devices/links/serial",
                simulator.serialRequest("COM9"), simulator.admin).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("real 模式真实回环：12001 真监听、粘连两帧计数为 2、关闭后端口释放")
    void realModeListensOnHardPortAndCountsLoopbackFrames() throws Exception {
        Assumptions.assumeTrue(portFree(ProtocolConstants.UWB_TCP_SERVER_PORT),
                "本机 " + ProtocolConstants.UWB_TCP_SERVER_PORT + " 已被占用：契约禁止换端口，"
                        + "跳过真实回环（释放端口后可重跑）");

        // 不覆盖 scds.gateway.mode → 走 application.yml 的 real 默认值
        try (App real = App.boot(ApplicationStartupSupport.newKeyPair(), null)) {
            assertThat(real.context.getBean(DeviceLinkAdmin.class)).isInstanceOf(RealDeviceLinkAdmin.class);
            assertThat(real.linkOf("UWB_TCP_SERVER").path("state").asText()).isEqualTo("CLOSED");

            HttpResponse<String> opened = real.post("/api/v1/devices/links/uwb",
                    real.mapper.createObjectNode().put("dataSource", "TCP_SERVER").put("host", "127.0.0.1"),
                    real.admin);
            assertThat(opened.statusCode()).isEqualTo(200);
            JsonNode openedStatus = real.mapper.readTree(opened.body());
            assertThat(openedStatus.path("state").asText()).isEqualTo("OPEN");
            assertThat(openedStatus.path("target").asText())
                    .isEqualTo("127.0.0.1:" + ProtocolConstants.UWB_TCP_SERVER_PORT);
            assertThat(openedStatus.path("fdCount").isNull()).as("还没有客户端接入").isTrue();

            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        ProtocolConstants.UWB_TCP_SERVER_PORT), 2000);
                // 两帧粘连一次写出：同时验证粘包分支经真实 Netty 链路后仍逐帧切分
                client.getOutputStream().write(Hex.decode(UWB_FRAME_A + UWB_FRAME_B));
                client.getOutputStream().flush();

                JsonNode linked = awaitFrames(real, 2);
                assertThat(linked.path("framesTotal").asLong()).isEqualTo(2);
                assertThat(linked.path("fdCount").asInt()).isEqualTo(1);
                assertThat(linked.path("lastFrameAt").isNull()).isFalse();
                assertThat(linked.path("droppedTotal").asLong()).isZero();
                assertThat(linked.path("state").asText()).isEqualTo("OPEN");

                // 关闭必须释放底层资源：状态回 CLOSED、计数清零
                HttpResponse<String> closed = real.delete("/api/v1/devices/links/uwb", real.admin);
                assertThat(closed.statusCode()).isEqualTo(200);
                assertThat(real.mapper.readTree(closed.body()).path("kind").asText())
                        .isEqualTo("UWB_TCP_SERVER");
                JsonNode afterClose = real.linkOf("UWB_TCP_SERVER");
                assertThat(afterClose.path("state").asText()).isEqualTo("CLOSED");
                assertThat(afterClose.path("target").isNull()).isTrue();
                assertThat(afterClose.path("framesTotal").asLong()).isZero();
            }

            assertThat(portFree(ProtocolConstants.UWB_TCP_SERVER_PORT))
                    .as("关闭后 %s 必须真的释放", ProtocolConstants.UWB_TCP_SERVER_PORT).isTrue();
        }
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 轮询 HTTP 快照直到 UWB_TCP_SERVER 收到期望帧数，或超时（超时也返回最后快照，由断言报错）。 */
    private static JsonNode awaitFrames(App app, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        JsonNode status = app.linkOf("UWB_TCP_SERVER");
        while (status.path("framesTotal").asLong() != expected && System.nanoTime() < deadline) {
            Thread.sleep(50);
            status = app.linkOf("UWB_TCP_SERVER");
        }
        return status;
    }

    /** 端口是否可绑定：真实回环用例的前置探测与「关闭后已释放」的判据共用同一动作。 */
    private static boolean portFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException busy) {
            return false;
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
         *                    用 {@code application.yml} 的默认值
         */
        private static App boot(KeyPair key, String gatewayMode) throws Exception {
            ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
            if (gatewayMode != null) {
                environment.getPropertySources().addFirst(
                        new MapPropertySource("hw-link-http-it", Map.of("scds.gateway.mode", gatewayMode)));
            }
            return new App(environment, key);
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
                request.header("Authorization", "Bearer " + bearer);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private JsonNode linksSnapshot() throws Exception {
            HttpResponse<String> response = get("/api/v1/devices/links", admin);
            assertThat(response.statusCode()).isEqualTo(200);
            return mapper.readTree(response.body());
        }

        private JsonNode linkOf(String kind) throws Exception {
            for (JsonNode link : linksSnapshot()) {
                if (kind.equals(link.path("kind").asText())) {
                    return link;
                }
            }
            throw new AssertionError("链路快照里没有 " + kind + "：" + linksSnapshot());
        }

        private JsonNode serialRequest(String portName) {
            return mapper.createObjectNode().put("portName", portName);
        }

        private ObjectNode uwbTcpRequest(String host, Integer port) {
            ObjectNode body = mapper.createObjectNode().put("dataSource", "TCP_SERVER");
            if (host != null) {
                body.put("host", host);
            }
            if (port != null) {
                body.put("port", port);
            }
            return body;
        }

        private ObjectNode uwbSerialRequest(String portName) {
            ObjectNode body = mapper.createObjectNode().put("dataSource", "SERIAL");
            if (portName != null) {
                body.put("serialPortName", portName);
            }
            return body;
        }

        private void assertProblem(HttpResponse<String> response, int status, String code, String path) {
            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .startsWith("application/problem+json");
            JsonNode body;
            try {
                body = mapper.readTree(response.body());
            } catch (Exception failure) {
                throw new AssertionError("问题响应不是合法 JSON：" + response.body(), failure);
            }
            assertThat(body.path("status").asInt()).isEqualTo(status);
            assertThat(body.path("code").asText()).isEqualTo(code);
            assertThat(body.path("instance").asText()).isEqualTo(path);
            assertThat(body.path("traceId").asText()).isNotBlank();
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
