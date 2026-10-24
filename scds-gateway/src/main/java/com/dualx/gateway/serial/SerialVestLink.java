package com.dualx.gateway.serial;

import com.dualx.core.domain.LinkConfig;
import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.port.HardwareEventSink;
import com.dualx.gateway.link.LinkRegistry;
import com.dualx.gateway.link.LinkSlot;
import com.dualx.protocol.ProtocolConstants;
import com.dualx.protocol.serial.SerialFrame;
import com.dualx.protocol.serial.SerialFramer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 马甲串口链路（协议 A）：字节流 → 完整帧（上行）与下行帧 → 物理串口。
 *
 * <p><b>三条线程，各管一件事</b>：</p>
 * <ul>
 *   <li><b>读线程</b>：半阻塞读 → {@link SerialFramer} 切帧 → {@link HardwareEventSink}。
 *       只做分片重组，不做落库、解算或任何阻塞动作，因此不会被业务拖死。</li>
 *   <li><b>写线程</b>：从有界命令队列取帧并写出。队列满时 {@code send} 直接失败
 *       （{@code COMMAND_QUEUE_FULL}），<b>不静默丢帧</b>——判死、判活、充弹这类指令
 *       丢一条就是现场事故，宁可让调用方收到失败。</li>
 *   <li><b>轮询线程</b>：按协议 §1.1 的 200ms 周期逐马甲下发缓存的轮询配置帧。
 *       轮询是链路自持行为，不需要 core 每 200ms 触发一次。</li>
 * </ul>
 *
 * <p><b>轮询配置从哪来</b>：轮询配置帧（{@code 02 00 ...}，14B）里带着 HP / 弹药 / 队伍 / 模式，
 * 这些值由核心侧的态势决定。核心通过 {@code sendVestCommand} 下发该帧时，链路把它按马甲编号
 * 存进缓存（{@link #send}），此后每 200ms 重发——同一份字节，不需要核心维护定时器。</p>
 *
 * <p><b>已知缺口</b>：{@code droppedTotal} / {@code crcErrorTotal} 恒为 0。
 * {@link SerialFramer} 把「坏帧重同步」收敛在内部，对外只给合法帧，不回传丢弃计数；
 * 要准确统计必须改协议库的返回值形状，而协议库是冻结区（有黄金样本回归）。
 * 故此处不臆造数字：字段保留在契约里，等 STATE 段一并决定是改协议库还是改用原始字节统计。</p>
 */
public final class SerialVestLink {

    private static final Logger log = LoggerFactory.getLogger(SerialVestLink.class);

    private static final LinkKind KIND = LinkKind.SERIAL_VEST;

    /** 轮询配置帧标识（协议 §2.2：{@code 02 00 <num:2BE> ...}）。 */
    private static final byte POLL_CONFIG_BYTE_0 = (byte) 0x02;
    private static final byte POLL_CONFIG_BYTE_1 = 0x00;

    /** 读缓冲：协议 A 单帧最长 18B（ASCII 36 字符），512B 足够一次装下多帧粘连。 */
    private static final int READ_BUFFER_SIZE = 512;

    /** 写队列阻塞等待上限，仅用于让写线程有机会响应中断。 */
    private static final long WRITE_QUEUE_POLL_MS = 200L;

    private final LinkRegistry registry;
    private final HardwareEventSink sink;
    private final Function<LinkConfig, SerialChannel> channelFactory;
    private final int pollIntervalMs;
    private final int sendQueueCapacity;

    private final BlockingQueue<byte[]> outbox;
    /** 马甲编号 → 该马甲的轮询配置帧；核心下发新配置时整体替换。 */
    private final Map<Integer, byte[]> pollConfigs = new ConcurrentHashMap<>();
    /** 物理写入口保护：写线程与轮询线程共用一个串口，必须逐帧串行。 */
    private final Object writeLock = new Object();

    private volatile SerialChannel channel;
    private volatile Thread readerThread;
    private volatile Thread writerThread;
    private volatile Thread pollThread;

    public SerialVestLink(LinkRegistry registry,
                          HardwareEventSink sink,
                          Function<LinkConfig, SerialChannel> channelFactory,
                          int pollIntervalMs,
                          int sendQueueCapacity) {
        this.registry = registry;
        this.sink = sink;
        this.channelFactory = channelFactory;
        this.pollIntervalMs = pollIntervalMs;
        this.sendQueueCapacity = sendQueueCapacity;
        this.outbox = new ArrayBlockingQueue<>(Math.max(1, sendQueueCapacity));
    }

    public LinkStatus status() {
        return registry.slot(KIND).status();
    }

    /**
     * 打开链路。
     *
     * @throws BusinessException {@code DEVICE_LINK_ALREADY_OPEN}（已开或开启中）、
     *                           {@code DEVICE_LINK_SERIAL_BUSY}（打开被系统拒绝或端口被占）、
     *                           {@code VALIDATION_FAILED}（未指定串口名）
     */
    public LinkStatus open(LinkConfig config) {
        String portName = requirePortName(config);
        int baudRate = config.serialBaudRate() != null
                ? config.serialBaudRate()
                : ProtocolConstants.SERIAL_BAUD_RATE;

        // 先占坑再碰硬件：冲突（409）必须在打开物理端口之前判出来
        LinkSlot slot = registry.beginOpen(KIND, portName, portName, baudRate);

        SerialChannel opened = null;
        try {
            opened = channelFactory.apply(config);
            opened.open();
        } catch (RuntimeException | IOException failure) {
            closeQuietly(opened);
            registry.abortOpen(KIND, failure.getMessage());
            log.warn("马甲串口打开失败：{}", portName, failure);
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY,
                    "打开马甲串口失败：" + portName + "（不存在或已被占用）");
        }

        this.channel = opened;
        registry.finishOpen(KIND);
        startThreads(slot, opened);
        sink.onLinkStateChanged(KIND, LinkState.OPEN, null);
        log.info("马甲串口已打开：{} @ {} bps", portName, baudRate);
        return slot.status();
    }

    /** 关闭链路：幂等；未打开时无副作用（不重复上报状态变更）。 */
    public LinkStatus close() {
        LinkSlot slot = registry.slot(KIND);
        boolean wasActive = slot.active();
        SerialChannel current = channel;
        channel = null;
        stopThreads();
        closeQuietly(current);
        dropTransportState();
        registry.close(KIND);
        if (wasActive) {
            sink.onLinkStateChanged(KIND, LinkState.CLOSED, null);
            log.info("马甲串口已关闭");
        }
        return slot.status();
    }

    /**
     * 下发一帧（字节已由 {@code com.dualx.protocol} 编码好，链路不改字节）。
     *
     * <p>轮询配置帧会被额外计入轮询缓存；其余指令只入队发送一次。</p>
     *
     * @throws BusinessException {@code COMMAND_QUEUE_FULL} 队列已满，帧未下发
     */
    public void send(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "下行帧不能为空");
        }
        if (isPollConfig(payload)) {
            pollConfigs.put(vestNumOf(payload), payload.clone());
        }
        if (!outbox.offer(payload)) {
            throw new BusinessException(ApiErrorCode.COMMAND_QUEUE_FULL,
                    "串口发送队列已满（容量 " + sendQueueCapacity + "），指令未下发");
        }
    }

    // ==================== 线程 ====================

    private void startThreads(LinkSlot slot, SerialChannel opened) {
        SerialFramer framer = new SerialFramer();
        readerThread = start("vest-serial-reader", () -> readLoop(slot, opened, framer));
        writerThread = start("vest-serial-writer", () -> writeLoop(opened));
        pollThread = start("vest-serial-poll", () -> pollLoop(opened));
    }

    private static Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void readLoop(LinkSlot slot, SerialChannel opened, SerialFramer framer) {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int read = opened.read(buffer);
                if (read <= 0) {
                    continue;
                }
                List<SerialFrame> frames = framer.feed(Arrays.copyOf(buffer, read));
                for (SerialFrame frame : frames) {
                    slot.recordFrame();
                    sink.onSerialFrame(frame, KIND);
                }
            }
        } catch (IOException failure) {
            fail(opened, "马甲串口读失败", failure);
        }
    }

    private void writeLoop(SerialChannel opened) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] payload = outbox.poll(WRITE_QUEUE_POLL_MS, TimeUnit.MILLISECONDS);
                if (payload != null) {
                    write(opened, payload);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            fail(opened, "马甲串口写失败", failure);
        }
    }

    private void pollLoop(SerialChannel opened) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long startedAt = System.nanoTime();
                for (byte[] config : pollConfigs.values()) {
                    write(opened, config);
                }
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
                long idleMs = pollIntervalMs - elapsedMs;
                if (idleMs > 0) {
                    Thread.sleep(idleMs);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            fail(opened, "马甲串口轮询写失败", failure);
        }
    }

    /** 逐帧串行写出；命令帧与轮询帧共用同一串口，只在单帧粒度上互斥。 */
    private void write(SerialChannel opened, byte[] payload) throws IOException {
        synchronized (writeLock) {
            opened.write(payload);
        }
    }

    /**
     * 运行中失败：释放串口与队列、落 {@code ERROR} 并通知核心。
     *
     * <p>用 {@code channel == null} 作「已收尾」标记，因此关闭流程触发的写失败不会二次报错。</p>
     */
    private void fail(SerialChannel failed, String message, Exception cause) {
        if (channel != failed) {
            return;
        }
        channel = null;
        stopThreads();
        closeQuietly(failed);
        dropTransportState();
        String detail = message + "：" + cause.getMessage();
        registry.abortOpen(KIND, detail);
        sink.onLinkStateChanged(KIND, LinkState.ERROR, detail);
        log.warn("{}", detail, cause);
    }

    private void stopThreads() {
        interrupt(readerThread);
        interrupt(writerThread);
        interrupt(pollThread);
        readerThread = null;
        writerThread = null;
        pollThread = null;
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void dropTransportState() {
        outbox.clear();
        pollConfigs.clear();
    }

    private static void closeQuietly(SerialChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (RuntimeException ignored) {
            // 关闭失败无需上报：链路状态已经落 CLOSED / ERROR，端口随后由系统回收
        }
    }

    // ==================== 帧识别 ====================

    private static String requirePortName(LinkConfig config) {
        String portName = config.serialPortName();
        if (portName == null || portName.isBlank()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "未指定马甲串口");
        }
        return portName.trim();
    }

    /** 协议 §2.2 轮询配置帧：{@code 02 00 <num:2BE> ...}。 */
    private static boolean isPollConfig(byte[] payload) {
        return payload.length >= 4
                && payload[0] == POLL_CONFIG_BYTE_0
                && payload[1] == POLL_CONFIG_BYTE_1;
    }

    private static int vestNumOf(byte[] pollConfig) {
        return ((pollConfig[2] & 0xFF) << 8) | (pollConfig[3] & 0xFF);
    }
}
