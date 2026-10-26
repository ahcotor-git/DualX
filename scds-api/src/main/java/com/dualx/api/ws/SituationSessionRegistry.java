package com.dualx.api.ws;

import com.dualx.core.push.SituationEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 态势 WebSocket <b>会话表</b>（设计 [02](../../../../docs/design/02-push-ports.md) §4.5~§4.8）。
 *
 * <p><b>为什么与会话者分开成 bean</b>：handler 需要 {@code register}，广播器需要遍历全部会话。
 * 若让广播器兼管会话表，依赖就成环（{@code WebSocketConfig → handler → broadcaster → handler}）；
 * 拆出本类后依赖是单向的 {@code handler → registry ← broadcaster}（设计 02 §4.6）。</p>
 *
 * <p><b>每会话一条有界队列 + 一个虚拟线程</b>（设计 02 §4.7 约定 2）：{@code WebSocketSession.sendMessage}
 * 既非线程安全（同会话并发发送会抛 {@code TEXT_PARTIAL_WRITING}），又是同步阻塞的（TCP 缓冲满会挂住调用者）。
 * 因此投递只做 {@code offer}（非阻塞、不抛），真正的写下沉到该会话专属的虚拟线程——一个读不动的客户端
 * 只堵住它自己的线程，不拖住其它会话，也不阻塞 core 的事件循环（端口约定 1）。</p>
 *
 * <p><b>PONG 也走同一条队列</b>：只让发送线程碰 {@code sendMessage}，是「同会话唯一写者」这条约束的全部意义；
 * 若保活应答直接在容器读线程上写，就又把并发写与阻塞带回来了。</p>
 *
 * <p><b>丢帧后自动收敛为全量</b>：队满丢一帧后，后续帧的 revision 与客户端已收到的对不上，
 * 发送线程据此改推一帧 {@code full=true}（{@link #fullSnapshot}），客户端凭该帧的 revision 直接对齐。
 * 于是「丢帧」不需要客户端做任何特殊处理，也不需要改契约（设计 02 §4.7）。</p>
 */
public class SituationSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SituationSessionRegistry.class);

    /**
     * 空闲扫描周期：契约的 30s 是<b>断开阈值</b>，本任务只负责到点发现它；5s 一拍足够，
     * 再密也不会让断开更早发生，只会更频繁地遍历会话表。
     */
    static final long IDLE_SCAN_INTERVAL_MS = 5000L;

    /**
     * 空闲断开的关闭码：刻意用 RFC 6455 的 1000（正常关闭）+ reason。
     *
     * <p>契约只为一种异常关闭定义了特殊语义（4000 = 超窗 → 客户端改全量），这里不新造码：
     * 客户端对空闲断开的处理与任何断线一样——重连 + 低频 Poll 兜底，那是契约已强制的路径。</p>
     */
    private static final CloseStatus IDLE_CLOSE = new CloseStatus(CloseStatus.NORMAL.getCode(), "IDLE_TIMEOUT");

    private final ObjectMapper objectMapper;
    private final int queueCapacity;
    private final long idleTimeoutMs;
    private final LongSupplier nowMs;
    private final Supplier<SituationEnvelope> fullSnapshot;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 观测 1：活跃会话数（用于发现连接泄漏）。 */
    private final AtomicLong activeSessions = new AtomicLong();

    /** 观测 2：因队满丢弃的帧数（正常应为 0；非 0 说明背压真的在发生）。 */
    private final AtomicLong droppedFrames = new AtomicLong();

    /** 观测 3：因 revision 不连续改推全量的次数（正常应为 0）。 */
    private final AtomicLong resyncs = new AtomicLong();

    /** 发送失败次数（连接坏了但尚未被容器关掉时会累加，用于诊断，不属于设计要求的三个观测之一）。 */
    private final AtomicLong pushErrors = new AtomicLong();

    /**
     * @param objectMapper   信封序列化器（Spring Boot 的 ObjectMapper，已按契约配好 RFC 3339 / UTC）
     * @param queueCapacity  每会话待发帧上限（{@code scds.push.send-queue-capacity}），必须 &gt; 0
     * @param idleTimeoutMs  无上行主动断开阈值（{@code scds.push.idle-timeout-ms}，契约值 30000），必须 &gt; 0
     * @param nowMs          墙钟毫秒来源；只服务于「距上次上行过了多久」这一个判断，与演习时间轴无关
     * @param fullSnapshot   取当前全量帧的<b>懒</b>来源（不连续时改推它）；用 {@link Supplier} 而非直接注入工厂，
     *                       是为了让本 bean 能在 core 装配 {@code SituationStateService} 的过程中被创建而不成环
     *                       （推送端口 → 广播器 → 本表 → 工厂 → 读侧 → 状态机 会绕回正在创建的状态机）
     */
    public SituationSessionRegistry(ObjectMapper objectMapper, int queueCapacity, long idleTimeoutMs,
                                    LongSupplier nowMs, Supplier<SituationEnvelope> fullSnapshot) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("scds.push.send-queue-capacity 必须 > 0，实际 " + queueCapacity);
        }
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("scds.push.idle-timeout-ms 必须 > 0，实际 " + idleTimeoutMs);
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.queueCapacity = queueCapacity;
        this.idleTimeoutMs = idleTimeoutMs;
        this.nowMs = Objects.requireNonNull(nowMs, "nowMs");
        this.fullSnapshot = Objects.requireNonNull(fullSnapshot, "fullSnapshot");
    }

    // ------------------------------------------------------------------ 注册 / 注销

    /**
     * 注册一个刚握手的会话，并把首帧/补齐帧交给它（设计 02 §4.5）。
     *
     * <p><b>首帧与订阅的原子衔接</b>：补齐帧先入会话的「首发」位置、随后才把会话放进会话表，
     * 因此并发广播只能排在补齐帧之后，不会出现「增量跑在全量之前」。补齐帧不经背压队列（它们不受
     * 丢帧策略管，最多 {@code scds.situation.revision-window} 条），也保证了逐帧补齐不会被队满截断。</p>
     *
     * <p>反过来「读快照与注册之间」的窗口不需要额外屏障：那时产生的变化会以更小的 revision 落在补齐帧里，
     * 或直接以跳号被连续性检查发现并改推全量——两条路都收敛（判据 8）。</p>
     *
     * @param initialFrames 首帧或补齐帧，按 revision 升序；调用方保证非空
     */
    public void register(WebSocketSession ws, List<SituationEnvelope> initialFrames) {
        Objects.requireNonNull(ws, "ws");
        Objects.requireNonNull(initialFrames, "initialFrames");
        Session session = new Session(ws, List.copyOf(initialFrames));
        Session previous = sessions.put(ws.getId(), session);
        if (previous != null) {
            // 同一 id 重复注册理论上不会发生（id 由容器分配）；真发生时关掉旧的，避免留下无人关闭的发送线程。
            log.warn("态势 WS 会话 id 重复注册，关闭旧会话：id={}", ws.getId());
            previous.shutdown(CloseStatus.NORMAL);
        }
        activeSessions.incrementAndGet();
        session.start();
        log.debug("态势 WS 会话已注册：id={}，首帧数={}，当前活跃={}",
                ws.getId(), initialFrames.size(), activeSessions.get());
    }

    /** 注销会话（容器回调或本类主动关闭后调用）；未知 id 静默忽略——关闭失败的回调也会走到这里。 */
    public void unregister(String sessionId) {
        remove(sessionId, CloseStatus.NORMAL);
    }

    private void remove(String sessionId, CloseStatus status) {
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return;
        }
        activeSessions.decrementAndGet();
        session.shutdown(status);
        log.debug("态势 WS 会话已注销：id={}，当前活跃={}", sessionId, activeSessions.get());
    }

    /** 记一次上行（保活，设计 02 §4.7 约定 3）；未知 id 静默忽略。 */
    public void touch(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.markInbound();
        }
    }

    // ------------------------------------------------------------------ 投递

    /**
     * 广播一帧（广播器入口）。
     *
     * <p><b>非阻塞、不抛异常</b>（端口约定 1）：本方法在 core 的事件处理线程上被调用，队满即丢帧并计数，
     * 任何异常都在此吞掉——推送失败不该让上游的一帧合法数据丢失，更不该影响上游。</p>
     */
    public void broadcast(SituationEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        for (Session session : sessions.values()) {
            if (!session.offer(Outbound.of(envelope))) {
                droppedFrames.incrementAndGet();
            }
        }
    }

    /** 向单个已注册会话投递补齐帧（{@code RESYNC} 用）；未注册或队满时丢帧并计数。 */
    public void enqueue(String sessionId, List<SituationEnvelope> frames) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            log.debug("态势 WS 补齐跳过：会话已不在表中 id={}", sessionId);
            return;
        }
        for (SituationEnvelope frame : frames) {
            if (!session.offer(Outbound.of(frame))) {
                droppedFrames.incrementAndGet();
            }
        }
    }

    /** 向单个会话投递一条控制帧（{@code PONG}）；走同一条发送线程，避免同会话并发写。 */
    public void sendControl(String sessionId, String payload) {
        Session session = sessions.get(sessionId);
        if (session != null && !session.offer(Outbound.control(payload))) {
            droppedFrames.incrementAndGet();
        }
    }

    // ------------------------------------------------------------------ 观测

    /** 当前活跃会话数。 */
    public long activeSessionCount() {
        return activeSessions.get();
    }

    /** 因队满丢弃的帧数。 */
    public long droppedFrameCount() {
        return droppedFrames.get();
    }

    /** 因 revision 不连续改推全量的次数。 */
    public long resyncCount() {
        return resyncs.get();
    }

    /** 发送失败次数。 */
    public long pushErrorCount() {
        return pushErrors.get();
    }

    // ------------------------------------------------------------------ 保活与停机

    /**
     * 空闲会话扫描（设计 02 §4.7 约定 3）：契约写明「服务端 30s 无上行会主动断开」，
     * 断开后会话随之注销，活跃会话数回到真实值（判据 5）。
     *
     * <p>{@code fixedDelay} 而非 {@code fixedRate}：上一拍没扫完时不该堆叠节拍。</p>
     */
    @Scheduled(fixedDelay = IDLE_SCAN_INTERVAL_MS)
    public void closeIdleSessions() {
        long now = nowMs.getAsLong();
        for (Session session : sessions.values()) {
            if (now - session.lastInboundAtMs > idleTimeoutMs) {
                log.info("态势 WS 空闲超时断开：id={}，无上行 {}ms（阈值 {}ms）",
                        session.ws.getId(), now - session.lastInboundAtMs, idleTimeoutMs);
                remove(session.ws.getId(), IDLE_CLOSE);
            }
        }
    }

    /**
     * 停机收尾（设计 02 §4.8）：<b>不广播任何「我要关了」帧</b>，直接关闭连接。
     *
     * <p>依据是契约的硬约束本身——WS 不得替代 Poll，客户端对断线的处理路径（重连 + 低频 Poll）已被强制存在，
     * 再定义一种优雅关闭帧只会多一条客户端必须实现的分支。这里只做两件事：注销会话、中断发送线程，避免线程泄漏。</p>
     */
    @PreDestroy
    public void close() {
        for (String sessionId : List.copyOf(sessions.keySet())) {
            remove(sessionId, CloseStatus.NORMAL);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ 会话

    /** 待发的一项：态势信封，或一条控制帧（{@code PONG}）。二者共用同一队列与同一写者。 */
    private record Outbound(SituationEnvelope envelope, String control) {

        static Outbound of(SituationEnvelope envelope) {
            return new Outbound(envelope, null);
        }

        static Outbound control(String payload) {
            return new Outbound(null, payload);
        }
    }

    /**
     * 单个会话：有界队列 + 虚拟发送线程。
     *
     * <p>{@code lastSentRevision} 只由发送线程读写（单写者），故不加锁。</p>
     */
    private final class Session {

        private final WebSocketSession ws;
        private final List<SituationEnvelope> initialFrames;
        private final BlockingQueue<Outbound> queue;
        private final Thread sender;

        private volatile long lastInboundAtMs;
        private volatile boolean open = true;

        Session(WebSocketSession ws, List<SituationEnvelope> initialFrames) {
            this.ws = ws;
            this.initialFrames = initialFrames;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.lastInboundAtMs = nowMs.getAsLong();
            this.sender = Thread.ofVirtual().name("ws-situation-" + ws.getId()).unstarted(this::run);
        }

        void start() {
            sender.start();
        }

        void markInbound() {
            lastInboundAtMs = nowMs.getAsLong();
        }

        /** 非阻塞入队；已关闭或队满返回 false，由调用方计数（不阻塞、不抛）。 */
        boolean offer(Outbound outbound) {
            return open && queue.offer(outbound);
        }

        void shutdown(CloseStatus status) {
            open = false;
            queue.clear();
            if (Thread.currentThread() != sender) {
                sender.interrupt();
            }
            closeQuietly(ws, status);
        }

        private void run() {
            // 补齐帧的末条 revision 就是客户端补齐后的持有值，后续增量按它做连续性检查。
            long lastSent = initialFrames.isEmpty()
                    ? Long.MIN_VALUE : initialFrames.get(initialFrames.size() - 1).revision();
            for (SituationEnvelope frame : initialFrames) {
                if (!write(frame)) {
                    return;
                }
            }
            while (open) {
                Outbound next;
                try {
                    next = queue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (next == null) {
                    continue;
                }
                if (next.control() != null) {
                    if (!writeText(next.control())) {
                        return;
                    }
                    continue;
                }
                SituationEnvelope envelope = next.envelope();
                if (!envelope.full() && envelope.revision() != lastSent + 1) {
                    // 丢过帧（队满或被关断过）：不推这一帧，改推当前全量，客户端凭它的 revision 直接对齐。
                    resyncs.incrementAndGet();
                    envelope = fullSnapshot.get();
                    log.debug("态势 WS 帧不连续，改推全量：id={}，lastSent={}，改为 revision={}",
                            ws.getId(), lastSent, envelope.revision());
                }
                if (!write(envelope)) {
                    return;
                }
                lastSent = envelope.revision();
            }
        }

        private boolean write(SituationEnvelope envelope) {
            try {
                return writeText(objectMapper.writeValueAsString(envelope));
            } catch (IOException e) {
                // JsonProcessingException 也走这里：序列化失败同样不该让发送线程活在一个永远发不出帧的会话上。
                pushErrors.incrementAndGet();
                log.warn("态势 WS 帧序列化/发送失败，关闭该会话：id={}", ws.getId(), e);
                remove(ws.getId(), CloseStatus.NORMAL);
                return false;
            }
        }

        private boolean writeText(String payload) {
            if (!ws.isOpen()) {
                remove(ws.getId(), CloseStatus.NORMAL);
                return false;
            }
            try {
                ws.sendMessage(new TextMessage(payload));
                return true;
            } catch (IOException e) {
                pushErrors.incrementAndGet();
                log.debug("态势 WS 发送失败，关闭该会话：id={}", ws.getId(), e);
                remove(ws.getId(), CloseStatus.NORMAL);
                return false;
            }
        }
    }

    /** 关闭连接时吞掉异常：能走到这里的会话都已经不可用，关闭失败不改变任何结论。 */
    private static void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            if (ws.isOpen()) {
                ws.close(status);
            }
        } catch (IOException e) {
            log.debug("态势 WS 关闭失败（忽略）：id={}", ws.getId(), e);
        }
    }
}
