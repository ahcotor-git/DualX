package com.dualx.gateway.uwb;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.link.LinkSlot;
import com.dualx.gateway.serial.JSerialChannel;
import com.dualx.gateway.serial.SerialChannel;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.uwb.UwbFrame;
import com.dualx.protocol.uwb.UwbFramer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * UWB 走串口的接入链路（协议 B，{@link LinkKind#UWB_SERIAL}）。
 *
 * <p>与 {@link UwbTcpServerLink} <b>共用同一个</b> {@link UwbFramer}，不新增专用成帧逻辑
 * （PROTOCOL.md §3.2）；两者互斥，由 {@link LinkRegistry} 判定，切换必须先关闭。</p>
 *
 * <p>单线程：读串口 → 成帧 → sink。UWB 只有上行坐标流，没有下行指令，
 * 因此不像马甲串口那样需要写队列与轮询线程。</p>
 *
 * <p><b>波特率</b>：默认 {@value #DEFAULT_BAUD_RATE}（设计候选值，见 docs/design/05-hw-link.md §4.4），
 * 数据库里配置了 {@code serialBaudRate} 时以后者为准。该值不在 PROTOCOL.md 里，
 * 因此不放 {@link ProtocolConstants}。</p>
 *
 * <p><b>已知缺口</b>：{@code droppedTotal} / {@code crcErrorTotal} 恒为 0，原因同
 * {@link UwbTcpServerLink}——{@link UwbFramer} 不回传逐位判定，改它属冻结区。</p>
 */
public final class UwbSerialLink {

    private static final Logger log = LoggerFactory.getLogger(UwbSerialLink.class);

    private static final LinkKind KIND = LinkKind.UWB_SERIAL;

    /**
     * UWB 串口默认波特率（05-hw-link §4.4）。
     *
     * <p>对仿真适配器（{@code com.dualx.gateway.sim.SimulatedLinkAdapter}）公开：
     * 仿真与真实串口必须用同一个默认值，两处各写一个 460800 迟早会对不上。</p>
     */
    public static final int DEFAULT_BAUD_RATE = 460800;

    /** 读缓冲：一次可装下多帧粘连（41B × 12）。 */
    private static final int READ_BUFFER_SIZE = 512;

    private final LinkRegistry registry;
    private final HardwareEventSink sink;
    private final Function<LinkConfig, SerialChannel> channelFactory;

    private volatile SerialChannel channel;
    private volatile Thread readerThread;

    public UwbSerialLink(LinkRegistry registry, HardwareEventSink sink) {
        this(registry, sink, UwbSerialLink::openJSerial);
    }

    /** 供测试注入假串口。 */
    UwbSerialLink(LinkRegistry registry, HardwareEventSink sink, Function<LinkConfig, SerialChannel> channelFactory) {
        this.registry = registry;
        this.sink = sink;
        this.channelFactory = channelFactory;
    }

    public LinkStatus status() {
        return registry.slot(KIND).status();
    }

    /**
     * 打开串口。
     *
     * @throws BusinessException {@code VALIDATION_FAILED}（未指定串口名）、
     *                           {@code DEVICE_LINK_ALREADY_OPEN}（本模式或 UWB TCP 模式已开）、
     *                           {@code DEVICE_LINK_SERIAL_BUSY}（串口被系统拒绝或已被占用）
     */
    public LinkStatus open(LinkConfig config) {
        String portName = requirePortName(config);
        int baudRate = config.serialBaudRate() != null ? config.serialBaudRate() : DEFAULT_BAUD_RATE;

        // 先占坑再碰硬件：与马甲串口共用同一张串口归属表，冲突必须在这里判出来
        LinkSlot slot = registry.beginOpen(KIND, portName, portName, baudRate);

        SerialChannel opened = null;
        try {
            opened = channelFactory.apply(config);
            opened.open();
        } catch (RuntimeException | IOException failure) {
            closeQuietly(opened);
            registry.abortOpen(KIND, failure.getMessage());
            log.warn("UWB 串口打开失败：{}", portName, failure);
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY,
                    "打开 UWB 串口失败：" + portName + "（不存在或已被占用）");
        }

        this.channel = opened;
        registry.finishOpen(KIND);
        readerThread = startReader(slot, opened);
        sink.onLinkStateChanged(KIND, LinkState.OPEN, null);
        log.info("UWB 串口已打开：{} @ {} bps", portName, baudRate);
        return slot.status();
    }

    /** 关闭链路：幂等；未打开时无副作用（不重复上报状态变更）。 */
    public LinkStatus close() {
        LinkSlot slot = registry.slot(KIND);
        boolean wasActive = slot.active();
        SerialChannel current = channel;
        channel = null;
        interrupt(readerThread);
        readerThread = null;
        closeQuietly(current);
        registry.close(KIND);
        if (wasActive) {
            sink.onLinkStateChanged(KIND, LinkState.CLOSED, null);
            log.info("UWB 串口已关闭");
        }
        return slot.status();
    }

    // ==================== 读线程 ====================

    private Thread startReader(LinkSlot slot, SerialChannel opened) {
        Thread thread = new Thread(() -> readLoop(slot, opened), "uwb-serial-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void readLoop(LinkSlot slot, SerialChannel opened) {
        UwbFramer framer = new UwbFramer();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int read = opened.read(buffer);
                if (read <= 0) {
                    continue;
                }
                List<UwbFrame> frames = framer.feed(Arrays.copyOf(buffer, read));
                for (UwbFrame frame : frames) {
                    slot.recordFrame();
                    sink.onUwbFrame(frame, KIND);
                }
            }
        } catch (IOException failure) {
            fail(opened, "UWB 串口读失败", failure);
        }
    }

    /** 运行中失败：释放串口、落 {@code ERROR} 并通知核心；用 {@code channel == null} 作「已收尾」标记。 */
    private void fail(SerialChannel failed, String message, Exception cause) {
        if (channel != failed) {
            return;
        }
        channel = null;
        interrupt(readerThread);
        readerThread = null;
        closeQuietly(failed);
        String detail = message + "：" + cause.getMessage();
        registry.abortOpen(KIND, detail);
        sink.onLinkStateChanged(KIND, LinkState.ERROR, detail);
        log.warn("{}", detail, cause);
    }

    // ==================== 工具 ====================

    private static SerialChannel openJSerial(LinkConfig config) {
        int baudRate = config.serialBaudRate() != null ? config.serialBaudRate() : DEFAULT_BAUD_RATE;
        return new JSerialChannel(config.serialPortName(), baudRate,
                config.serialFlowControlRtsCts(), ProtocolConstants.POLLING_TIMEOUT_MS);
    }

    private static String requirePortName(LinkConfig config) {
        String portName = config.serialPortName();
        if (portName == null || portName.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "未指定 UWB 串口");
        }
        return portName.trim();
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(SerialChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // 关闭失败无需上报：链路状态已落 CLOSED / ERROR，端口随后由系统回收
        }
    }
}
