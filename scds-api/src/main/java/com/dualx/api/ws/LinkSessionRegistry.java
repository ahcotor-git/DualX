package com.dualx.api.ws;

import com.dualx.core.domain.LinkStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
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
 * 链路状态 WebSocket <b>会话表</b>（设计 [02](../../../../docs/design/02-push-ports.md) §4.6、§4.7、§4.8）。
 *
 * <p>与 {@link SituationSessionRegistry} 同一套骨架：会话表单独成 bean（否则
 * {@code WebSocketConfig → handler → broadcaster → handler} 成环），每会话一条有界队列 +
 * 一个虚拟线程，投递只 {@code offer}、写下沉到该会话自己的线程。设计 02 §4.6 明确
 * 「两处 fan-out 骨架相似，但先各写一份；只有出现第三处才抽公共基类」——这里不复用态势侧的类。</p>
 *
 * <p><b>与态势侧的本质差异：链路没有 revision 维度</b>。态势是「增量 + 连续性检查 + 丢帧后
 * 改推全量」，链路是「每次变化都推完整快照」——因此这里没有 {@code lastSentRevision}、
 * 没有跳号收敛，也不需要 {@code RESYNC}。客户端拿到的每一帧都可直接整体替换本地状态。</p>
 *
 * <p><b>首帧窗口</b>：{@code register} 先读快照、再把会话放进会话表，两步之间发生的变化本会话
 * 收不到。因为这个窗口只有微秒级、且<b>每次推送都是全量快照</b>（任何后续变化都会把最新值
 * 一并带回来），加上契约强制的低频 Poll 兜底，缺口不会长期存在；态势侧靠 revision 连续性
 * 检查收敛，链路侧靠「全量 + Poll」这两条。</p>
 *
 * <p><b>保活</b>：沿用态势侧的同一策略与同一配置项（{@code scds.push.idle-timeout-ms}）。
 * 契约只为态势通道写明了「30s 无上行主动断开」，但链路通道同样定义了 {@code PING}
 * 上行——没有超时的保活没有意义，且不设超时会让会话与虚拟线程永久泄漏。</p>
 */
public class LinkSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LinkSessionRegistry.class);

    /** 空闲扫描周期：与态势侧一致，只负责到点发现超时会话（见 {@link SituationSessionRegistry}）。 */
    static final long IDLE_SCAN_INTERVAL_MS = 5000L;

    private static final CloseStatus IDLE_CLOSE = new CloseStatus(CloseStatus.NORMAL.getCode(), "IDLE_TIMEOUT");

    private final ObjectMapper objectMapper;
    private final int queueCapacity;
    private final long idleTimeoutMs;
    private final LongSupplier nowMs;

    /** 首帧来源：HTTP {@code getLinks} 与推送读的是同一份 {@code DeviceLinkAdmin.snapshot()}。 */
    private final Supplier<List<LinkStatus>> snapshot;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 观测 1：活跃会话数（用于发现连接泄漏）。 */
    private final AtomicLong activeSessions = new AtomicLong();

    /** 观测 2：因队满丢弃的帧数（正常应为 0；非 0 说明背压真的在发生）。 */
    private final AtomicLong droppedFrames = new AtomicLong();

    /** 序列化失败与发送失败次数（用于诊断，不属于设计要求的那两个观测）。 */
    private final AtomicLong pushErrors = new AtomicLong();

    /**
     * @param objectMapper  列表序列化器（Spring Boot 的 ObjectMapper，已按契约配好时间格式）
     * @param queueCapacity 每会话待发帧上限（{@code scds.push.send-queue-capacity}），必须 &gt; 0
     * @param idleTimeoutMs 无上行主动断开阈值（{@code scds.push.idle-timeout-ms}），必须 &gt; 0
     * @param nowMs         墙钟毫秒来源；只服务于「距上次上行过了多久」这一个判断
     * @param snapshot      取当前链路状态快照的<b>懒</b>来源：链路真值在网关进程里，api 只通过
     *                      {@code DeviceLinkAdmin} 端口读它。用 {@link Supplier} 而非直接注入
     *                      {@code DeviceLinkAdmin}，是为了让本 bean 能在「网关注册表正在被创建」
     *                      的过程中被创建而不成环（注册表 → 推送端口 → 广播器 → 本表 → 端口）。
     */
    public LinkSessionRegistry(ObjectMapper objectMapper, int queueCapacity, long idleTimeoutMs,
                               LongSupplier nowMs, Supplier<List<LinkStatus>> snapshot) {
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
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    // ------------------------------------------------------------------ 注册 / 注销

    /** 注册一个刚握手的会话，并把当前完整快照作为首帧交给它。 */
    public void register(WebSocketSession ws) {
        Objects.requireNonNull(ws, "ws");
        Session session = new Session(ws, serialize(snapshot.get()));
        Session previous = sessions.put(ws.getId(), session);
        if (previous != null) {
            // 同一 id 重复注册理论上不会发生（id 由容器分配）；真发生时关掉旧的，避免留下无人关闭的发送线程。
            log.warn("链路 WS 会话 id 重复注册，关闭旧会话：id={}", ws.getId());
            previous.shutdown(CloseStatus.NORMAL);
        }
        activeSessions.incrementAndGet();
        session.start();
        log.debug("链路 WS 会话已注册：id={}，当前活跃={}", ws.getId(), activeSessions.get());
    }

    /** 注销会话（容器回调或本类主动关闭后调用）；未知 id 静默忽略。 */
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
        log.debug("链路 WS 会话已注销：id={}，当前活跃={}", sessionId, activeSessions.get());
    }

    /** 记一次上行（保活）；未知 id 静默忽略。 */
    public void touch(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.markInbound();
        }
    }

    // ------------------------------------------------------------------ 投递

    /**
     * 广播一份完整快照（广播器入口，由网关的链路变化触发）。
     *
     * <p><b>非阻塞、不抛异常</b>（端口约定 1）：本方法在网关的链路状态迁移路径上被调用，
     * 队满即丢帧并计数，任何异常都在此吞掉——推送失败不该影响链路开关的语义。</p>
     *
     * <p>序列化在广播线程上做<b>一次</b>（三个元素的列表），而不是每会话各做一次：
     * 链路帧很小，省下的是 N 次序列化，代价是广播线程上多几微秒。</p>
     */
    public void broadcast(List<LinkStatus> payload) {
        Objects.requireNonNull(payload, "payload");
        String json = serialize(payload);
        if (json == null) {
            return;
        }
        for (Session session : sessions.values()) {
            if (!session.offer(json)) {
                droppedFrames.incrementAndGet();
            }
        }
    }

    /** 向单个会话投递一条控制帧（{@code PONG}）；走同一条发送线程，避免同会话并发写。 */
    public void sendControl(String sessionId, String payload) {
        Session session = sessions.get(sessionId);
        if (session != null && !session.offer(payload)) {
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

    /** 序列化/发送失败次数。 */
    public long pushErrorCount() {
        return pushErrors.get();
    }

    // ------------------------------------------------------------------ 保活与停机

    /** 空闲会话扫描：超过阈值无上行即关闭并注销（与态势侧同一策略）。 */
    @Scheduled(fixedDelay = IDLE_SCAN_INTERVAL_MS)
    public void closeIdleSessions() {
        long now = nowMs.getAsLong();
        for (Session session : sessions.values()) {
            if (now - session.lastInboundAtMs > idleTimeoutMs) {
                log.info("链路 WS 空闲超时断开：id={}，无上行 {}ms（阈值 {}ms）",
                        session.ws.getId(), now - session.lastInboundAtMs, idleTimeoutMs);
                remove(session.ws.getId(), IDLE_CLOSE);
            }
        }
    }

    /**
     * 停机收尾（设计 02 §4.8）：<b>不广播任何「我要关了」帧</b>，直接关闭连接——
     * 契约已强制客户端「断线即重连 + 低频 Poll」，再定义一种优雅关闭帧只会多一条客户端分支。
     */
    @PreDestroy
    public void close() {
        for (String sessionId : List.copyOf(sessions.keySet())) {
            remove(sessionId, CloseStatus.NORMAL);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ 会话

    /** 单个会话：有界队列 + 虚拟发送线程。队列元素即已序列化的 JSON 文本（数据帧与控制帧同形）。 */
    private final class Session {

        private final WebSocketSession ws;
        private final String initialPayload;
        private final BlockingQueue<String> queue;
        private final Thread sender;

        private volatile long lastInboundAtMs;
        private volatile boolean open = true;

        Session(WebSocketSession ws, String initialPayload) {
            this.ws = ws;
            this.initialPayload = initialPayload;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
            this.lastInboundAtMs = nowMs.getAsLong();
            this.sender = Thread.ofVirtual().name("ws-links-" + ws.getId()).unstarted(this::run);
        }

        void start() {
            sender.start();
        }

        void markInbound() {
            lastInboundAtMs = nowMs.getAsLong();
        }

        /** 非阻塞入队；已关闭或队满返回 false，由调用方计数（不阻塞、不抛）。 */
        boolean offer(String payload) {
            return open && queue.offer(payload);
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
            if (initialPayload != null && !write(initialPayload)) {
                return;
            }
            while (open) {
                String payload;
                try {
                    payload = queue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (payload != null && !write(payload)) {
                    return;
                }
            }
        }

        private boolean write(String payload) {
            if (!ws.isOpen()) {
                remove(ws.getId(), CloseStatus.NORMAL);
                return false;
            }
            try {
                ws.sendMessage(new TextMessage(payload));
                return true;
            } catch (IOException e) {
                pushErrors.incrementAndGet();
                log.debug("链路 WS 发送失败，关闭该会话：id={}", ws.getId(), e);
                remove(ws.getId(), CloseStatus.NORMAL);
                return false;
            }
        }
    }

    /** 序列化整份快照；失败即丢帧并计数（推送失败不改链路状态，也不抛给上游）。 */
    private String serialize(List<LinkStatus> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            pushErrors.incrementAndGet();
            log.warn("链路状态快照序列化失败（丢弃该帧）：{}", e.toString());
            return null;
        }
    }

    /** 关闭连接时吞掉异常：能走到这里的会话都已不可用，关闭失败不改变任何结论。 */
    private static void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            if (ws.isOpen()) {
                ws.close(status);
            }
        } catch (IOException e) {
            log.debug("链路 WS 关闭失败（忽略）：id={}", ws.getId(), e);
        }
    }
}
