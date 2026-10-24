package com.dualx.gateway.sim;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.port.SerialPortInfo;
import com.dualx.gateway.config.GatewayProperties;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.uwb.UwbSerialLink;
import com.dualx.protocol.Hex;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.serial.DownlinkCommands;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.serial.SerialFrameType;
import com.dualx.protocol.uwb.UwbFrame;
import com.dualx.protocol.uwb.UwbFramer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仿真适配器单测：证明它在无硬件前提下与真链路<b>同一套</b>语义
 * （状态机、重开 409、串口独占、UWB 互斥、12001 硬约束、快照恒三条），
 * 以及仿真专用的两项能力（上行注入、下行逐字节断言）。
 *
 * <p>帧字节取自协议库黄金样本（协议 A：{@code sticky_packets.json}；协议 B：
 * {@code golden_samples.json}），<b>不自造字节</b>——自算校验和等于自证。</p>
 */
class SimulatedLinkAdapterTest {

    /** 黄金样本：{@code ff03} 短版心跳（马甲 2 / 主弹药 30 / HP 100 / 副弹药 30）。 */
    private static final String HEARTBEAT = "ff03000002001e64001ea4affa";

    /** 黄金样本：{@code ff04} GPS + {@code ff03} 心跳粘连在一批字节里。 */
    private static final String STICKY = "ff0400000207136d12017e8822c7affaff03000002001e64001ea4affa";

    /** 黄金样本 {@code uwb41.samples[0]}：41B，帧头 AA 29、帧尾 55、校验自洽。 */
    private static final String UWB_A =
            "aa29021300000000000000341200005613000000000000000000000000000000000000000000009755";

    private final LinkRegistry registry = new LinkRegistry();
    private final RecordingSink sink = new RecordingSink();
    private final SimulatedLinkAdapter sim = new SimulatedLinkAdapter(registry, sink,
            List.of(new SerialPortInfo("COM7", "仿真马甲串口")));

    // ==================== 配置默认值 ====================

    @Test
    void designDefaultsAreRealModeAndMatchTheDesignTable() {
        GatewayProperties props = new GatewayProperties();

        assertEquals(GatewayProperties.Mode.REAL, props.getMode());
        assertFalse(props.isSimulator(), "漏配 mode 必须走硬件，不能静默进仿真");
        assertEquals(ProtocolConstants.SEND_QUEUE_LIMIT, props.getSerial().getSendQueueCapacity());
        assertEquals(GatewayProperties.Serial.Overflow.REJECT, props.getSerial().getSendQueueOverflow());
        assertEquals(4, props.getUwb().getTcpBacklog());
        assertEquals(0, props.getUwb().getWorkerThreads());
        assertFalse(props.isAutoOpenOnStartup());

        props.setMode(GatewayProperties.Mode.SIMULATOR);
        assertTrue(props.isSimulator());
        props.setMode(null);
        assertEquals(GatewayProperties.Mode.REAL, props.getMode(), "空值绑定应落回 REAL");

        // 容量写错就在启动期失败，而不是静默改成一个更小的值让现场看到的与配置不符
        assertThrows(IllegalArgumentException.class, () -> props.getSerial().setSendQueueCapacity(0));
        assertThrows(IllegalArgumentException.class, () -> props.getUwb().setTcpBacklog(0));
        assertThrows(IllegalArgumentException.class, () -> props.getUwb().setWorkerThreads(-1));
    }

    // ==================== 链路生命周期与冲突 ====================

    @Test
    void serialOpenExposesRealStateAndReopenIsAConflict() {
        LinkStatus opened = sim.openSerial(serialConfig("COM7", null));

        assertEquals(LinkKind.SERIAL_VEST, opened.kind());
        assertEquals(LinkState.OPEN, opened.state());
        assertEquals("COM7", opened.target());
        assertEquals(ProtocolConstants.SERIAL_BAUD_RATE, opened.baudRate());
        assertEquals(List.of("SERIAL_VEST:OPEN"), sink.stateChanges);

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> sim.openSerial(serialConfig("COM7", null)));
        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, conflict.code());

        // 同一物理串口不能被两条链路同时持有：这里换成 UWB 串口来抢 COM7
        BusinessException busy = assertThrows(BusinessException.class,
                () -> sim.openUwbSerial(uwbSerialConfig("COM7", null)));
        assertEquals(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY, busy.code());
    }

    @Test
    void blankPortNameIsRejectedBeforeTouchingTheRegistry() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> sim.openSerial(serialConfig("   ", null)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, failure.code());
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.SERIAL_VEST).state());
    }

    @Test
    void closeIsIdempotentAndSnapshotAlwaysHasThreeKinds() {
        sim.openSerial(serialConfig("COM7", null));
        sim.injectSerial(HEARTBEAT);

        assertEquals(3, sim.snapshot().size());
        assertEquals(1L, statusOf(LinkKind.SERIAL_VEST).framesTotal());

        LinkStatus closed = sim.closeSerial();

        assertEquals(LinkState.CLOSED, closed.state());
        assertEquals(0L, statusOf(LinkKind.SERIAL_VEST).framesTotal(), "关闭后计数必须归零");
        assertNull(statusOf(LinkKind.SERIAL_VEST).target());

        sim.closeSerial();
        assertEquals(List.of("SERIAL_VEST:OPEN", "SERIAL_VEST:CLOSED"), sink.stateChanges,
                "重复关闭不得再报状态变更");
        assertEquals(3, sim.snapshot().size(), "未配置过的链路也要在快照里占一条");
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.UWB_TCP_SERVER).state());
    }

    // ==================== UWB ====================

    @Test
    void uwbTcpServerRejectsPortOtherThanHardConstraint() {
        BusinessException port = assertThrows(BusinessException.class,
                () -> sim.openUwbTcpServer(uwbTcpConfig(9999)));

        assertEquals(ApiErrorCode.VALIDATION_FAILED, port.code());
        assertEquals(LinkState.CLOSED, statusOf(LinkKind.UWB_TCP_SERVER).state(), "校验失败不能留下半开状态");

        LinkStatus opened = sim.openUwbTcpServer(uwbTcpConfig(null));

        assertEquals("0.0.0.0:" + ProtocolConstants.UWB_TCP_SERVER_PORT, opened.target());
        assertEquals(1, opened.fdCount(), "仿真中视作数据源已接入，与真实单连接语义一致");
    }

    @Test
    void uwbModesAreMutuallyExclusive() {
        sim.openUwbSerial(uwbSerialConfig("COM3", null));
        assertEquals(UwbSerialLink.DEFAULT_BAUD_RATE, statusOf(LinkKind.UWB_SERIAL).baudRate());

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> sim.openUwbTcpServer(uwbTcpConfig(null)));
        assertEquals(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, conflict.code());

        // 切换接入方式必须先显式关闭
        sim.closeUwbSerial();
        assertEquals(LinkState.OPEN, sim.openUwbTcpServer(uwbTcpConfig(null)).state());
    }

    @Test
    void uwbFramesReachSinkThroughTheSameFramer() {
        sim.openUwbTcpServer(uwbTcpConfig(null));

        assertTrue(sim.injectUwb(UWB_A.substring(0, 4)).isEmpty(), "半包不应提前成帧");
        assertTrue(sink.uwbFrames.isEmpty());

        List<UwbFrame> frames = sim.injectUwb(UWB_A.substring(4));

        assertEquals(1, frames.size());
        // 与协议库独立复算的帧逐字段一致：证明仿真用的是同一个 UwbFramer，不是自己的解析
        UwbFrame expected = UwbFramer.split(Hex.decode(UWB_A)).get(0);
        assertEquals(expected.vestNum(), frames.get(0).vestNum());
        assertArrayEquals(expected.rangesMm(), frames.get(0).rangesMm());
        assertArrayEquals(Hex.decode(UWB_A), frames.get(0).raw());
        assertEquals(LinkKind.UWB_TCP_SERVER, sink.uwbSources.get(0));
        assertEquals(1L, statusOf(LinkKind.UWB_TCP_SERVER).framesTotal());
    }

    // ==================== 上下行 ====================

    @Test
    void stickySampleIsFramedAndCountedThroughTheSimulator() {
        sim.openSerial(serialConfig("COM7", null));

        List<SerialFrame> frames = sim.injectSerial(STICKY);

        assertEquals(2, frames.size());
        assertEquals(SerialFrameType.GPS, frames.get(0).type());
        assertEquals(SerialFrameType.HEARTBEAT, frames.get(1).type());
        assertEquals(2, sink.serialFrames.size());
        assertEquals(LinkKind.SERIAL_VEST, sink.serialSources.get(0));
        assertEquals(2L, statusOf(LinkKind.SERIAL_VEST).framesTotal());
    }

    @Test
    void halfSampleIsHeldUntilCompleted() {
        sim.openSerial(serialConfig("COM7", null));

        assertTrue(sim.injectSerial("ff03").isEmpty());
        assertTrue(sim.injectSerial("000002001e64001e").isEmpty());
        assertTrue(sink.serialFrames.isEmpty(), "半包不应提前成帧");
        assertEquals(0L, statusOf(LinkKind.SERIAL_VEST).framesTotal());

        List<SerialFrame> frames = sim.injectSerial("a4affa");

        assertEquals(1, frames.size());
        SerialFrame.Heartbeat heartbeat = (SerialFrame.Heartbeat) frames.get(0);
        assertEquals(2, heartbeat.vestNum());
        assertEquals(30, heartbeat.ammo1());
        assertEquals(100, heartbeat.hp());
        assertEquals(30, heartbeat.ammo2());
        assertEquals(1L, statusOf(LinkKind.SERIAL_VEST).framesTotal());
    }

    @Test
    void sentCommandsAreRecordedVerbatimAndEmptyPayloadIsRejected() {
        byte[] die = DownlinkCommands.die(1);
        sim.sendVestCommand(7, die);

        List<SimulatedLinkAdapter.SentCommand> sent = sim.sentCommands();

        assertEquals(1, sent.size());
        assertEquals(7, sent.get(0).vestNum());
        assertEquals(Hex.encode(die), sent.get(0).hex());

        // 记录的是克隆：调用方改原数组不该影响已记录字节（否则现场复用缓冲会写出错帧）
        die[0] = (byte) 0x00;
        assertEquals(Hex.encode(DownlinkCommands.die(1)), sim.sentCommands().get(0).hex());

        BusinessException empty = assertThrows(BusinessException.class,
                () -> sim.sendVestCommand(7, new byte[0]));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, empty.code());

        sim.clearSentCommands();
        assertTrue(sim.sentCommands().isEmpty());
    }

    @Test
    void injectingFramesOnAClosedLinkIsRejected() {
        assertThrows(IllegalStateException.class, () -> sim.injectSerial(HEARTBEAT));
        assertThrows(IllegalStateException.class, () -> sim.injectUwb(UWB_A));

        assertTrue(sink.serialFrames.isEmpty());
        assertTrue(sink.uwbFrames.isEmpty());
    }

    @Test
    void listedSerialPortsComeFromTheInjectedFixture() {
        assertEquals(List.of(new SerialPortInfo("COM7", "仿真马甲串口")), sim.listSerialPorts());
    }

    // ==================== 夹具 ====================

    private LinkStatus statusOf(LinkKind kind) {
        return sim.snapshot().stream()
                .filter(status -> status.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("snapshot 缺少链路：" + kind));
    }

    private static LinkConfig serialConfig(String portName, Integer baudRate) {
        return new LinkConfig(LinkKind.SERIAL_VEST, portName, baudRate, false, null, null, false, null, null, null);
    }

    private static LinkConfig uwbSerialConfig(String portName, Integer baudRate) {
        return new LinkConfig(LinkKind.UWB_SERIAL, portName, baudRate, false, null, null, false, null, null, null);
    }

    private static LinkConfig uwbTcpConfig(Integer port) {
        return new LinkConfig(LinkKind.UWB_TCP_SERVER, null, null, false, null, port, false, null, null, null);
    }

    /** 记录型 sink：验证网关把什么交给了核心，而不验证核心怎么处理。 */
    private static final class RecordingSink implements HardwareEventSink {

        private final List<SerialFrame> serialFrames = new CopyOnWriteArrayList<>();
        private final List<LinkKind> serialSources = new CopyOnWriteArrayList<>();
        private final List<UwbFrame> uwbFrames = new CopyOnWriteArrayList<>();
        private final List<LinkKind> uwbSources = new CopyOnWriteArrayList<>();
        private final List<String> stateChanges = new CopyOnWriteArrayList<>();

        @Override
        public void onSerialFrame(SerialFrame frame, LinkKind source) {
            serialFrames.add(frame);
            serialSources.add(source);
        }

        @Override
        public void onUwbFrame(UwbFrame frame, LinkKind source) {
            uwbFrames.add(frame);
            uwbSources.add(source);
        }

        @Override
        public void onLinkStateChanged(LinkKind kind, LinkState state, String message) {
            stateChanges.add(kind.name() + ":" + state.name());
        }
    }
}
