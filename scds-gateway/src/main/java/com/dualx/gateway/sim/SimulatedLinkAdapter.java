package com.dualx.gateway.sim;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.core.port.SerialPortInfo;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.link.LinkSlot;
import com.dualx.gateway.uwb.UwbSerialLink;
import com.dualx.protocol.Hex;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.serial.SerialFramer;
import com.dualx.protocol.uwb.UwbFrame;
import com.dualx.protocol.uwb.UwbFramer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 无硬件仿真适配器：{@code scds.gateway.mode=simulator} 时替换真链路
 * （设计 docs/design/05-hw-link.md §4.6）。
 *
 * <p><b>它不是「另一套逻辑」，而是同一套链路语义的无硬件实现</b>：状态机、重开冲突（409
 * {@code DEVICE_LINK_ALREADY_OPEN}）、UWB 两种模式互斥、串口独占、{@code snapshot()} 恒三
 * 条的形状，全部走同一个 {@link LinkRegistry}；上行仍经 {@link SerialFramer} /
 * {@link UwbFramer} 成帧后才回调 sink——所以仿真里通过的接线与成帧判据，与真链路是同一套。</p>
 *
 * <p><b>它替不掉的东西</b>：真实串口的 115200 时序、UWB 盒子经 12001 的连续坐标、
 * 容量与长稳（D10）。仿真通过<b>不得</b>写成现场通过（§7）。</p>
 *
 * <p><b>线程模型</b>：本适配器不起任何线程——帧由测试或开发工具通过 {@code inject*}
 * 同步投喂，下行由 {@code sendVestCommand} 同步记录。确定性是这里的第一要求：
 * 若仿真也跑 200ms 轮询线程，断言下行字节就变成与时间赛跑。</p>
 */
public final class SimulatedLinkAdapter implements DeviceLinkAdmin {

    private static final Logger log = LoggerFactory.getLogger(SimulatedLinkAdapter.class);

    /** 下行记录上限：防止长时间开发会话把内存吃满；超出后丢最旧的一条。 */
    private static final int SENT_HISTORY_LIMIT = 1000;

    private final LinkRegistry registry;
    private final HardwareEventSink sink;
    private final List<SerialPortInfo> serialPorts;

    /** 成帧器各一份实例；协议库声明非线程安全，故投喂与重置都在 {@link #framingLock} 内。 */
    private final SerialFramer serialFramer = new SerialFramer();
    private final UwbFramer uwbFramer = new UwbFramer();
    private final Object framingLock = new Object();

    private final Deque<SentCommand> sent = new ArrayDeque<>();
    private final Object sentLock = new Object();

    /** 仿真下没有真串口，枚举结果为空；装配方可传入假端口让前端下拉框可点。 */
    public SimulatedLinkAdapter(LinkRegistry registry, HardwareEventSink sink) {
        this(registry, sink, List.of());
    }

    public SimulatedLinkAdapter(LinkRegistry registry, HardwareEventSink sink, List<SerialPortInfo> serialPorts) {
        this.registry = registry;
        this.sink = sink;
        this.serialPorts = List.copyOf(serialPorts);
    }

    // ==================== DeviceLinkAdmin ====================

    @Override
    public List<SerialPortInfo> listSerialPorts() {
        return serialPorts;
    }

    @Override
    public LinkStatus openSerial(LinkConfig config) {
        String portName = requirePortName(config, "马甲串口");
        int baudRate = config.serialBaudRate() != null
                ? config.serialBaudRate()
                : ProtocolConstants.SERIAL_BAUD_RATE;

        LinkSlot slot = registry.beginOpen(LinkKind.SERIAL_VEST, portName, portName, baudRate);
        resetFramer(LinkKind.SERIAL_VEST);
        registry.finishOpen(LinkKind.SERIAL_VEST);
        sink.onLinkStateChanged(LinkKind.SERIAL_VEST, LinkState.OPEN, null);
        log.info("[仿真] 马甲串口已打开：{} @ {} bps", portName, baudRate);
        return slot.status();
    }

    @Override
    public LinkStatus closeSerial() {
        return close(LinkKind.SERIAL_VEST);
    }

    @Override
    public LinkStatus openUwbTcpServer(LinkConfig config) {
        String host = hostOf(config);
        int port = portOf(config);

        LinkSlot slot = registry.beginOpen(LinkKind.UWB_TCP_SERVER, null, host + ":" + port, null);
        resetFramer(LinkKind.UWB_TCP_SERVER);
        registry.finishOpen(LinkKind.UWB_TCP_SERVER);
        // 仿真中视作数据源已接入：与真实「单连接汇聚」语义一致，让 fdCount 有确定的取值
        slot.updateConnections(1);
        sink.onLinkStateChanged(LinkKind.UWB_TCP_SERVER, LinkState.OPEN, null);
        log.info("[仿真] UWB TCP Server 已启动：{}:{}", host, port);
        return slot.status();
    }

    @Override
    public LinkStatus openUwbSerial(LinkConfig config) {
        String portName = requirePortName(config, "UWB 串口");
        int baudRate = config.serialBaudRate() != null
                ? config.serialBaudRate()
                : UwbSerialLink.DEFAULT_BAUD_RATE;

        LinkSlot slot = registry.beginOpen(LinkKind.UWB_SERIAL, portName, portName, baudRate);
        resetFramer(LinkKind.UWB_SERIAL);
        registry.finishOpen(LinkKind.UWB_SERIAL);
        sink.onLinkStateChanged(LinkKind.UWB_SERIAL, LinkState.OPEN, null);
        log.info("[仿真] UWB 串口已打开：{} @ {} bps", portName, baudRate);
        return slot.status();
    }

    @Override
    public LinkStatus closeUwbTcpServer() {
        return close(LinkKind.UWB_TCP_SERVER);
    }

    @Override
    public LinkStatus closeUwbSerial() {
        return close(LinkKind.UWB_SERIAL);
    }

    @Override
    public List<LinkStatus> snapshot() {
        return registry.snapshot();
    }

    @Override
    public void sendVestCommand(int vestNum, byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "下行帧不能为空");
        }
        byte[] recorded = payload.clone();
        synchronized (sentLock) {
            if (sent.size() >= SENT_HISTORY_LIMIT) {
                sent.removeFirst();
            }
            sent.addLast(new SentCommand(vestNum, recorded));
        }
        log.debug("[仿真] 下行帧已记录：马甲 {} {}", vestNum, Hex.encode(recorded));
    }

    // ==================== 仿真专用：上行注入与下行断言 ====================

    /**
     * 注入一批协议 A 的<b>上行 ASCII HEX 文本</b>（如黄金样本
     * {@code "ff03000002001e64001ea4affa"}）。
     *
     * <p>可拆成多次调用复现半包：残包留在成帧器里，直到后续字节补齐。</p>
     *
     * @return 本次切出的完整帧（同时已回调 sink、已计入 {@code framesTotal}）
     * @throws IllegalStateException 马甲串口未开启
     */
    public List<SerialFrame> injectSerial(String asciiHex) {
        return injectSerial(asciiHex.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** {@link #injectSerial(String)} 的字节版：参数是线上原样的 ASCII HEX 字节。 */
    public List<SerialFrame> injectSerial(byte[] asciiHexChunk) {
        requireOpen(LinkKind.SERIAL_VEST);
        List<SerialFrame> frames;
        synchronized (framingLock) {
            frames = serialFramer.feed(asciiHexChunk);
        }
        LinkSlot slot = registry.slot(LinkKind.SERIAL_VEST);
        for (SerialFrame frame : frames) {
            slot.recordFrame();
            sink.onSerialFrame(frame, LinkKind.SERIAL_VEST);
        }
        return frames;
    }

    /**
     * 注入协议 B 的 41B 二进制帧（hex 文本，如黄金样本
     * {@code "aa29021300000000000000341200005613…9755"}）。
     *
     * <p>不需要传链路种类：两种 UWB 模式互斥，至多一种处于开启态，就用它。</p>
     *
     * @return 本次切出的完整帧（同时已回调 sink、已计入 {@code framesTotal}）
     * @throws IllegalStateException 两条 UWB 链路都未开启
     */
    public List<UwbFrame> injectUwb(String hex) {
        return injectUwb(Hex.decode(hex));
    }

    /** {@link #injectUwb(String)} 的字节版：参数是线上原样的二进制字节。 */
    public List<UwbFrame> injectUwb(byte[] chunk) {
        LinkKind kind = openUwbKind();
        List<UwbFrame> frames;
        synchronized (framingLock) {
            frames = uwbFramer.feed(chunk);
        }
        LinkSlot slot = registry.slot(kind);
        for (UwbFrame frame : frames) {
            slot.recordFrame();
            sink.onUwbFrame(frame, kind);
        }
        return frames;
    }

    /** 已记录的下行帧（副本；每条的 payload 是发送时的克隆）。 */
    public List<SentCommand> sentCommands() {
        synchronized (sentLock) {
            return List.copyOf(sent);
        }
    }

    /** 清空下行记录；需要「只看这一轮」的用例先清再断言。 */
    public void clearSentCommands() {
        synchronized (sentLock) {
            sent.clear();
        }
    }

    /** 一次下行发送的记录。 */
    public record SentCommand(int vestNum, byte[] payload) {

        /** payload 的 hex 文本，便于逐字节断言与日志比对。 */
        public String hex() {
            return Hex.encode(payload);
        }

        @Override
        public String toString() {
            return "SentCommand[vest=" + vestNum + " payload=" + hex() + "]";
        }
    }

    // ==================== 内部 ====================

    private LinkStatus close(LinkKind kind) {
        LinkSlot slot = registry.slot(kind);
        boolean wasActive = slot.active();
        registry.close(kind);
        resetFramer(kind);
        if (wasActive) {
            sink.onLinkStateChanged(kind, LinkState.CLOSED, null);
            log.info("[仿真] 链路已关闭：{}", kind);
        }
        return slot.status();
    }

    /** 与真实链路同一判据：请求端口必须是硬约束 12001，否则 400（不改硬约束）。 */
    private static int portOf(LinkConfig config) {
        Integer requested = config.uwbBindPort();
        if (requested != null && requested != ProtocolConstants.UWB_TCP_SERVER_PORT) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED,
                    "UWB TCP 监听端口固定为 " + ProtocolConstants.UWB_TCP_SERVER_PORT + "，不接受 " + requested);
        }
        return ProtocolConstants.UWB_TCP_SERVER_PORT;
    }

    private static String hostOf(LinkConfig config) {
        String host = config.uwbBindHost();
        return host == null || host.isBlank() ? ProtocolConstants.UWB_TCP_SERVER_HOST : host.trim();
    }

    private static String requirePortName(LinkConfig config, String what) {
        String portName = config.serialPortName();
        if (portName == null || portName.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "未指定" + what);
        }
        return portName.trim();
    }

    private LinkKind openUwbKind() {
        for (LinkKind kind : List.of(LinkKind.UWB_TCP_SERVER, LinkKind.UWB_SERIAL)) {
            if (registry.slot(kind).active()) {
                return kind;
            }
        }
        throw new IllegalStateException("两条 UWB 链路都未开启，无法注入上行帧");
    }

    /**
     * 注入前要求链路已开。
     *
     * <p>刻意用 {@link IllegalStateException} 而不是 {@code BusinessException}：{@code inject*}
     * 不对应任何 HTTP 端点，「链路未开」没有契约错误码可复用，而新增错误码必须先登记进
     * openapi 的 {@code Problem.code}——为一个测试辅助方法动契约不划算。</p>
     */
    private void requireOpen(LinkKind kind) {
        if (!registry.slot(kind).active()) {
            throw new IllegalStateException("链路未开启，无法注入上行帧：" + kind);
        }
    }

    private void resetFramer(LinkKind kind) {
        synchronized (framingLock) {
            if (kind == LinkKind.SERIAL_VEST) {
                serialFramer.reset();
            } else {
                uwbFramer.reset();
            }
        }
    }
}
