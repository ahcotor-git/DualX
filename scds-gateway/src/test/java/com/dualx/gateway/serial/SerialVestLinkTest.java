package com.dualx.gateway.serial;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.protocol.Team;
import com.dualx.protocol.serial.DownlinkCommands;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.serial.SerialFrameType;
import com.dualx.protocol.uwb.UwbFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 马甲串口链路单测：用假串口替身验证「分片重组 → 成帧 → sink」「轮询重发」「队列满即拒绝」
 * 与失败落 {@code ERROR}，全部不依赖真实硬件。
 *
 * <p>帧字节取自协议库的黄金样本 {@code scds-protocol/src/test/resources/samples/sticky_packets.json}，
 * 不自造字节，避免测试自己算错校验和还测得过。</p>
 */
class SerialVestLinkTest {

    /** 黄金样本：{@code ff03} 短版心跳（马甲 2 / 主弹药 30 / HP 100 / 副弹药 30）。 */
    private static final String HEARTBEAT = "ff03000002001e64001ea4affa";

    /** 黄金样本：{@code ff04} GPS + {@code ff03} 心跳粘连在一批字节里。 */
    private static final String STICKY = "ff0400000207136d12017e8822c7affaff03000002001e64001ea4affa";

    private static final int POLL_INTERVAL_MS = 50;

    private final LinkRegistry registry = new LinkRegistry();
    private final FakeChannel channel = new FakeChannel();
    private final RecordingSink sink = new RecordingSink();

    private SerialVestLink link;

    @AfterEach
    void tearDown() {
        channel.releaseWrites();
        if (link != null) {
            link.close();
        }
    }

    private SerialVestLink openLink(int sendQueueCapacity) {
        link = new SerialVestLink(registry, sink, config -> channel, POLL_INTERVAL_MS, sendQueueCapacity);
        link.open(serialConfig("COM7", 115200));
        return link;
    }

    private static LinkConfig serialConfig(String portName, Integer baudRate) {
        return new LinkConfig(LinkKind.SERIAL_VEST, portName, baudRate, false, null, null, false, null, null, null);
    }

    private static byte[] ascii(String hex) {
        return hex.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void openReportsTargetAndSinkGetsOpenThenClosed() {
        SerialVestLink opened = openLink(10);

        LinkStatus status = opened.status();
        assertEquals(LinkState.OPEN, status.state());
        assertEquals(LinkKind.SERIAL_VEST, status.kind());
        assertEquals("COM7", status.target());
        assertEquals(115200, status.baudRate());
        assertEquals(List.of("OPEN"), sink.stateChanges);

        opened.close();

        assertEquals(LinkState.CLOSED, opened.status().state());
        assertEquals(List.of("OPEN", "CLOSED"), sink.stateChanges);

        // 幂等：重复关闭不再触发状态回调，也不抛异常
        opened.close();
        assertEquals(List.of("OPEN", "CLOSED"), sink.stateChanges);
    }

    @Test
    void halfPacketsAreReassembledBeforeReachingSink() {
        SerialVestLink opened = openLink(10);

        channel.deliver(ascii("ff03"));
        channel.deliver(ascii("000002001e64001e"));
        assertNoFrameWithin(200);

        channel.deliver(ascii("a4affa"));

        await("心跳帧应到达 sink", () -> sink.frames.size() == 1);
        SerialFrame.Heartbeat heartbeat = (SerialFrame.Heartbeat) sink.frames.get(0);
        assertEquals(2, heartbeat.vestNum());
        assertEquals(30, heartbeat.ammo1());
        assertEquals(100, heartbeat.hp());
        assertEquals(30, heartbeat.ammo2());
        assertEquals(LinkKind.SERIAL_VEST, sink.sources.get(0));
        assertEquals(1L, opened.status().framesTotal());
    }

    @Test
    void stickyFramesArriveAsTwoFramesInOrder() {
        SerialVestLink opened = openLink(10);

        channel.deliver(ascii(STICKY));

        await("粘连两帧都应到达 sink", () -> sink.frames.size() == 2);
        assertEquals(SerialFrameType.GPS, sink.frames.get(0).type());
        assertEquals(SerialFrameType.HEARTBEAT, sink.frames.get(1).type());
        assertEquals(2L, opened.status().framesTotal());
    }

    @Test
    void pollConfigIsSentImmediatelyAndResentEveryCycle() {
        SerialVestLink opened = openLink(10);
        byte[] pollConfig = DownlinkCommands.pollConfig(1001, DownlinkCommands.MODE_AUTO, 100, 30, 30, Team.RED);

        opened.send(pollConfig);

        // 首帧走命令队列立刻下发，之后由 50ms 轮询线程重发
        await("轮询配置应被周期性重发", () -> channel.writeCount(pollConfig) >= 3);
        assertArrayEquals(pollConfig, channel.writes.get(0));
    }

    @Test
    void sendQueueIsBoundedAndReportsFailureInsteadOfDropping() {
        SerialVestLink opened = openLink(1);
        channel.blockWrites();

        byte[] die = DownlinkCommands.die(1);
        BusinessException failure = null;
        for (int attempt = 0; attempt < 50 && failure == null; attempt++) {
            try {
                opened.send(die);
            } catch (BusinessException rejected) {
                failure = rejected;
            }
        }

        if (failure == null) {
            fail("发送队列未按容量拒绝，指令会被静默堆积");
        }
        assertEquals(ApiErrorCode.COMMAND_QUEUE_FULL, failure.code());
        assertTrue(failure.getMessage().contains("未下发"));
    }

    @Test
    void readFailureLandsOnErrorAndNotifiesSink() {
        SerialVestLink opened = openLink(10);

        channel.failReads(new IOException("USB 串口已拔出"));

        await("读失败应落 ERROR", () -> opened.status().state() == LinkState.ERROR);
        assertEquals("ERROR", sink.stateChanges.get(sink.stateChanges.size() - 1));
        assertTrue(opened.status().errorMessage().contains("USB 串口已拔出"));
    }

    @Test
    void blankPortNameIsRejectedBeforeTouchingHardware() {
        link = new SerialVestLink(registry, sink, config -> channel, POLL_INTERVAL_MS, 10);

        BusinessException failure = assertThrows(BusinessException.class, () -> link.open(serialConfig("  ", null)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertEquals(LinkState.CLOSED, link.status().state());
    }

    @Test
    void openFailureIsReportedAsSerialBusyAndReleasesThePort() {
        link = new SerialVestLink(registry, sink, config -> channel, POLL_INTERVAL_MS, 10);
        channel.failOpens(new IOException("COM7 被占用"));

        BusinessException failure = assertThrows(BusinessException.class, () -> link.open(serialConfig("COM7", 115200)));

        assertEquals(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY, failure.code());
        assertEquals(LinkState.ERROR, link.status().state());
        // 打开失败必须释放串口归属：否则现场拔插后无法重试
        assertTrue(registry.beginOpen(LinkKind.UWB_SERIAL, "COM7", "COM7", 460800) != null);
    }

    private static void await(String what, BooleanSupplier condition) {
        await(what, condition, 3000);
    }

    /** 等待条件成立；超时即失败，避免用固定 sleep 造成偶发失败。 */
    private static void await(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        fail("等待超时：" + what);
    }

    /** 断言给定时限内没有任何帧到达 sink（用于验证半包不会提前成帧）。 */
    private void assertNoFrameWithin(int millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            assertTrue(sink.frames.isEmpty(), "半包不应提前成帧，却收到 " + sink.frames.size() + " 帧");
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /** 假串口：读侧由测试投喂字节，写侧只记录字节，并可被挂起以验证队列行为。 */
    private static final class FakeChannel implements SerialChannel {

        private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();
        private final CountDownLatch writeGate = new CountDownLatch(1);

        private volatile boolean open;
        private volatile boolean blockWrites;
        private volatile IOException readFailure;
        private volatile IOException openFailure;

        void deliver(byte[] chunk) {
            incoming.add(chunk);
        }

        void blockWrites() {
            blockWrites = true;
        }

        void releaseWrites() {
            writeGate.countDown();
        }

        void failReads(IOException failure) {
            readFailure = failure;
        }

        void failOpens(IOException failure) {
            openFailure = failure;
        }

        int writeCount(byte[] expected) {
            int count = 0;
            for (byte[] written : writes) {
                if (Arrays.equals(written, expected)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void open() throws IOException {
            if (openFailure != null) {
                throw openFailure;
            }
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            if (readFailure != null) {
                throw readFailure;
            }
            byte[] chunk = null;
            try {
                chunk = incoming.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            }
            if (readFailure != null) {
                throw readFailure;
            }
            if (chunk == null) {
                return 0;
            }
            System.arraycopy(chunk, 0, buffer, 0, chunk.length);
            return chunk.length;
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (blockWrites) {
                try {
                    writeGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("写入被中断", interrupted);
                }
            }
            if (!open) {
                throw new IOException("串口未打开");
            }
            writes.add(data.clone());
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public String target() {
            return "COM7";
        }
    }

    /** 记录型 sink：验证网关把什么交给了核心，而不验证核心怎么处理。 */
    private static final class RecordingSink implements HardwareEventSink {

        private final List<SerialFrame> frames = new CopyOnWriteArrayList<>();
        private final List<LinkKind> sources = new CopyOnWriteArrayList<>();
        private final List<String> stateChanges = new CopyOnWriteArrayList<>();
        private final List<UwbFrame> uwbFrames = new ArrayList<>();

        @Override
        public void onSerialFrame(SerialFrame frame, LinkKind source) {
            frames.add(frame);
            sources.add(source);
        }

        @Override
        public void onUwbFrame(UwbFrame frame, LinkKind source) {
            uwbFrames.add(frame);
        }

        @Override
        public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
            stateChanges.add(state.name());
        }
    }
}
