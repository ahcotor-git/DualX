package com.dualx.gateway.uwb;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.serial.SerialChannel;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.uwb.UwbFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * UWB 链路单测（协议 B）：TCP 模式用真实回环连接验证「接入 / 踢旧连接 / 分片重组 / 坏帧不炸链路」，
 * 串口模式用假串口验证与 TCP <b>共用同一套成帧与回调</b>。
 *
 * <p>帧字节取自 {@code scds-protocol/src/test/resources/samples/golden_samples.json} 的
 * {@code uwb41} 段前两条样本，不自造字节（自算校验和等于自证）。</p>
 *
 * <p>TCP 用例不占用硬约束端口 12001：{@link UwbTcpServerLink} 的包内构造器注入 {@code 0}
 * 让系统分配临时端口，避免与现场/本地实例抢口。12001 的端到端验证放在 H7 的 IT。</p>
 */
class UwbLinkTest {

    /** 黄金样本 {@code uwb41.samples[0]}：41B，帧头 AA 29、帧尾 55、校验自洽。 */
    private static final String UWB_A =
            "aa29021300000000000000341200005613000000000000000000000000000000000000000000009755";

    /** 黄金样本 {@code uwb41.samples[1]}：与 A 仅在一处测距字节与校验上不同。 */
    private static final String UWB_B =
            "aa29021100000000000000341200003c14000000000000000000000000000000000000000000007c55";

    private static final int FRAME_LEN = 41;

    private final LinkRegistry registry = new LinkRegistry();
    private final RecordingSink sink = new RecordingSink();

    private UwbTcpServerLink tcp;
    private UwbSerialLink serial;
    private final List<Socket> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
                // 测试收尾，无需上报
            }
        }
        clients.clear();
        if (tcp != null) {
            tcp.close();
        }
        if (serial != null) {
            serial.close();
        }
    }

    // ==================== TCP 模式 ====================

    @Test
    void tcpServerDeliversFramesFromLoopbackClient() throws IOException {
        tcp = new UwbTcpServerLink(registry, sink, 0);
        tcp.open(tcpConfig("127.0.0.1", null));
        assertEquals(LinkState.OPEN, tcp.status().state());
        // target 是「请求的监听端点」，注入 0 端口时即 host:0；真实绑定端口由 boundPort() 给出
        assertEquals("127.0.0.1:0", tcp.status().target());
        assertTrue(tcp.boundPort() > 0, "0 端口应由系统分配出真实端口");

        Socket client = connect(tcp.boundPort());
        client.getOutputStream().write(hex(UWB_A));
        client.getOutputStream().flush();

        await("UWB 帧应到达 sink", () -> sink.uwbFrames.size() == 1);
        assertArrayEquals(hex(UWB_A), sink.uwbFrames.get(0).raw());
        assertEquals(LinkKind.UWB_TCP_SERVER, sink.sources.get(0));
        assertEquals(1L, tcp.status().framesTotal());
        assertNotNull(tcp.status().lastFrameAt());
        assertNotNull(tcp.status().fdCount(), "TCP 模式必须给出当前接入连接数");
    }

    @Test
    void halfPacketOverTcpIsReassembled() throws IOException {
        tcp = new UwbTcpServerLink(registry, sink, 0);
        tcp.open(tcpConfig("127.0.0.1", null));

        Socket client = connect(tcp.boundPort());
        OutputStream out = client.getOutputStream();

        out.write(hex(UWB_A), 0, 20);
        out.flush();
        assertNoUwbFrameWithin(150);

        out.write(hex(UWB_A), 20, FRAME_LEN - 20);
        out.flush();

        await("补齐后半包后应出帧", () -> sink.uwbFrames.size() == 1);
        assertArrayEquals(hex(UWB_A), sink.uwbFrames.get(0).raw());
    }

    @Test
    void corruptedFrameIsDroppedWithoutBreakingTheLink() throws IOException {
        tcp = new UwbTcpServerLink(registry, sink, 0);
        tcp.open(tcpConfig("127.0.0.1", null));

        byte[] corrupted = hex(UWB_B);
        corrupted[FRAME_LEN - 2] ^= (byte) 0xFF;

        Socket client = connect(tcp.boundPort());
        OutputStream out = client.getOutputStream();
        out.write(new byte[]{0x01, 0x02, 0x03});
        out.write(corrupted);
        out.write(hex(UWB_A));
        out.flush();

        await("坏帧之后的好帧仍应到达", () -> sink.uwbFrames.size() == 1);
        assertArrayEquals(hex(UWB_A), sink.uwbFrames.get(0).raw());
        assertEquals(LinkState.OPEN, tcp.status().state(), "坏帧不得把链路打成 ERROR");
        assertEquals(1L, tcp.status().framesTotal(), "只有合法帧计入 framesTotal");
    }

    @Test
    void newConnectionKicksTheOldOne() throws IOException {
        tcp = new UwbTcpServerLink(registry, sink, 0);
        tcp.open(tcpConfig("127.0.0.1", null));

        Socket oldClient = connect(tcp.boundPort());
        oldClient.setSoTimeout(2000);
        await("首连接应被登记", () -> Integer.valueOf(1).equals(tcp.status().fdCount()));

        Socket newClient = connect(tcp.boundPort());
        await("新连接应接入并被计数", () -> {
            Integer count = tcp.status().fdCount();
            return count != null && count == 1;
        });

        assertEquals(-1, oldClient.getInputStream().read(), "旧连接必须被踢出（读到 EOF）");

        newClient.getOutputStream().write(hex(UWB_A));
        newClient.getOutputStream().flush();
        await("新连接的帧应到达 sink", () -> sink.uwbFrames.size() == 1);
        assertEquals(1, tcp.status().fdCount(), "踢旧接新之后接入数仍为 1");
    }

    @Test
    void unbindableHostLeavesTheLinkInErrorAndReleasesTheSlot() {
        tcp = new UwbTcpServerLink(registry, sink, 0);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> tcp.open(tcpConfig("192.0.2.1", null)));

        assertEquals(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY, failure.code());
        assertEquals(LinkState.ERROR, tcp.status().state());
        assertNotNull(tcp.status().errorMessage());

        // 失败必须释放槽位：否则现场换个可用地址重试会被判成 409「已开启」
        assertEquals(LinkState.OPEN, tcp.open(tcpConfig("127.0.0.1", null)).state());
    }

    @Test
    void requestedPortOtherThanHardConstraintIsRejected() {
        tcp = new UwbTcpServerLink(registry, sink);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> tcp.open(tcpConfig("127.0.0.1", 9999)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertEquals(LinkState.CLOSED, tcp.status().state(), "校验失败不得进入 OPENING");
    }

    // ==================== 串口模式 ====================

    @Test
    void serialModeUsesTheSameFramerAndSink() {
        FakeSerialChannel fake = new FakeSerialChannel();
        serial = new UwbSerialLink(registry, sink, config -> fake);
        serial.open(serialConfig("COM9", 460800));

        assertEquals(LinkState.OPEN, serial.status().state());
        assertEquals("COM9", serial.status().target());
        assertEquals(460800, serial.status().baudRate());

        fake.deliver(hex(UWB_A));
        fake.deliver(hex(UWB_B));

        await("两帧都应到达 sink", () -> sink.uwbFrames.size() == 2);
        assertArrayEquals(hex(UWB_A), sink.uwbFrames.get(0).raw());
        assertArrayEquals(hex(UWB_B), sink.uwbFrames.get(1).raw());
        assertEquals(List.of(LinkKind.UWB_SERIAL, LinkKind.UWB_SERIAL), sink.sources);
        assertEquals(2L, serial.status().framesTotal());

        serial.close();
        assertEquals(LinkState.CLOSED, serial.status().state());
        assertEquals(List.of("OPEN", "CLOSED"), sink.stateChanges);
    }

    @Test
    void serialModeRejectsBlankPortName() {
        serial = new UwbSerialLink(registry, sink, config -> new FakeSerialChannel());

        BusinessException failure = assertThrows(BusinessException.class, () -> serial.open(serialConfig("  ", null)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertEquals(LinkState.CLOSED, serial.status().state());
    }

    @Test
    void uwbModesAreMutuallyExclusive() {
        serial = new UwbSerialLink(registry, sink, config -> new FakeSerialChannel());
        serial.open(serialConfig("COM9", null));
        assertEquals(460800, serial.status().baudRate(), "未配置波特率时必须落默认值");

        tcp = new UwbTcpServerLink(registry, sink, 0);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> tcp.open(tcpConfig("127.0.0.1", null)));

        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, failure.code());
        assertEquals(LinkState.CLOSED, tcp.status().state());
    }

    // ==================== 夹具 ====================

    private Socket connect(int port) throws IOException {
        Socket client = new Socket("127.0.0.1", port);
        client.setTcpNoDelay(true);
        clients.add(client);
        return client;
    }

    private static LinkConfig tcpConfig(String host, Integer port) {
        return new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null, false, host, port, false, null, null, null);
    }

    private static LinkConfig serialConfig(String portName, Integer baudRate) {
        return new LinkConfig(LinkKind.UWB_SERIAL, portName, baudRate, false, null, null, false, null, null, null);
    }

    /** 协议 B 是二进制协议：hex 文本 → 真实字节（不能走 ASCII 通道）。 */
    private static byte[] hex(String hexText) {
        byte[] out = new byte[hexText.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hexText.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void assertNoUwbFrameWithin(int millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            assertTrue(sink.uwbFrames.isEmpty(), "半包不应提前成帧，却收到 " + sink.uwbFrames.size() + " 帧");
            sleep(10);
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        await(what, condition, 3000);
    }

    private static void await(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        fail("等待超时：" + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** 假串口：只验证「读出的字节被正确成帧」，UWB 没有下行故不需要写侧。 */
    private static final class FakeSerialChannel implements SerialChannel {

        private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private volatile boolean open;

        void deliver(byte[] chunk) {
            incoming.add(chunk);
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public int read(byte[] buffer) {
            byte[] chunk;
            try {
                chunk = incoming.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
            if (chunk == null) {
                return 0;
            }
            System.arraycopy(chunk, 0, buffer, 0, chunk.length);
            return chunk.length;
        }

        @Override
        public void write(byte[] data) {
            throw new UnsupportedOperationException("UWB 链路没有下行");
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public String target() {
            return "COM9";
        }
    }

    /** 记录型 sink：只验证网关交出的是哪一帧、来自哪条链路。 */
    private static final class RecordingSink implements HardwareEventSink {

        private final List<UwbFrame> uwbFrames = new CopyOnWriteArrayList<>();
        private final List<LinkKind> sources = new CopyOnWriteArrayList<>();
        private final List<String> stateChanges = new CopyOnWriteArrayList<>();

        @Override
        public void onSerialFrame(SerialFrame frame, LinkKind source) {
            // UWB 链路不应产生协议 A 的帧
            throw new AssertionError("UWB 链路不应回调 onSerialFrame");
        }

        @Override
        public void onUwbFrame(UwbFrame frame, LinkKind source) {
            uwbFrames.add(frame);
            sources.add(source);
        }

        @Override
        public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
            stateChanges.add(state.name());
        }
    }
}
