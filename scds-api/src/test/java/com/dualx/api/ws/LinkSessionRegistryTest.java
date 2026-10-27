package com.dualx.api.ws;

import com.dualx.core.domain.LinkKind;
import com.dualx.core.domain.LinkState;
import com.dualx.core.domain.LinkStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链路 WS 会话表的行为（设计 [02](../../../../../docs/design/02-push-ports.md) §4.6、§4.7、§4.8）。
 *
 * <p>纯单测：会话用 Mockito 替身，时钟用可控毫秒来源，不启 Spring、不占端口。这里证明的正是
 * 「只有装配起来才看得见」的几件事：<b>首帧是数组根类型的完整快照</b>（与 HTTP {@code getLinks}
 * 同构，判据 1 的链路侧）、每次变化推整份快照、队满丢帧并计数、空闲会被注销，以及
 * <b>一个读不动的会话不阻塞投递方、也不拖住其他会话</b>（判据 7）。</p>
 *
 * <p>与 {@link SituationSessionRegistryTest} 的刻意差异：链路没有 revision 维度，因此这里
 * 断言的是「帧体是长度恒为 {@link LinkKind#values()} 的数组」而<b>不是</b> revision 单调。
 * （设计 02 §4.6 明确两处骨架先各写一份，不复用同一个测试骨架。）</p>
 */
class LinkSessionRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 发出的全部帧体（顺序即发送顺序）。 */
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong nowMs = new AtomicLong(1_000_000L);

    private WebSocketSession ws;

    @BeforeEach
    void setUp() {
        sent.clear();
        nowMs.set(1_000_000L);
        ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("s1");
        when(ws.isOpen()).thenReturn(true);
    }

    private LinkSessionRegistry registry(int capacity, long idleTimeoutMs) {
        return new LinkSessionRegistry(objectMapper, capacity, idleTimeoutMs, nowMs::get, () -> snapshot(1));
    }

    /** 记录发出的每一帧；可选地让第一次发送阻塞在闩上（用于制造队满）。 */
    private void recordSends(CountDownLatch blockFirstSend) throws Exception {
        // sendMessage 返回 void，只能用 doAnswer(...).when(...) 的形态打桩。
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (blockFirstSend != null && calls.incrementAndGet() == 1) {
                blockFirstSend.await(5, TimeUnit.SECONDS);
            }
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));
    }

    /** 一份三种链路恒定各一条的快照；{@code frames} 只用来在帧体里留一个可断言的判别值。 */
    private static List<LinkStatus> snapshot(long frames) {
        return List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.OPEN, "COM7", 115200, null, null,
                        frames, 0L, 0L, null),
                new LinkStatus(LinkKind.UWB_TCP_SERVER, LinkState.CLOSED, null, null, null, null,
                        0L, 0L, 0L, null),
                new LinkStatus(LinkKind.UWB_SERIAL, LinkState.CLOSED, null, null, null, null,
                        0L, 0L, 0L, null));
    }

    private JsonNode frameAt(int index) {
        try {
            return objectMapper.readTree(sent.get(index));
        } catch (Exception e) {
            throw new IllegalStateException("帧体不是合法 JSON：" + sent.get(index), e);
        }
    }

    /** 从帧体里取首个链路的 {@code framesTotal}（用来确认第二帧带的是新值）。 */
    private long framesTotalOf(String payload) {
        try {
            return objectMapper.readTree(payload).get(0).path("framesTotal").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("帧体不是合法 JSON：" + payload, e);
        }
    }

    @Test
    @DisplayName("首帧是数组根类型的完整快照——与 HTTP getLinks 响应体同构（判据 1 链路侧）")
    void firstFrameIsAnArrayRootedSnapshot() throws Exception {
        recordSends(null);
        LinkSessionRegistry registry = registry(8, 30_000);

        registry.register(ws);

        awaitUntil(() -> sent.size() == 1);
        JsonNode root = frameAt(0);
        assertThat(root.isArray()).as("根类型必须是数组，不是单条 LinkStatus 对象").isTrue();
        assertThat(root.size()).isEqualTo(LinkKind.values().length);
        assertThat(root.get(0).path("kind").asText()).isEqualTo("SERIAL_VEST");
        assertThat(root.get(0).path("framesTotal").asLong()).isEqualTo(1L);
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("每次变化推整份快照：客户端整体替换本地状态，不做单条合并")
    void broadcastPushesWholeSnapshot() throws Exception {
        recordSends(null);
        LinkSessionRegistry registry = registry(8, 30_000);
        registry.register(ws);
        awaitUntil(() -> sent.size() == 1);

        registry.broadcast(snapshot(42));

        awaitUntil(() -> sent.size() == 2);
        JsonNode root = frameAt(1);
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(LinkKind.values().length);
        assertThat(root.get(0).path("framesTotal").asLong()).as("第二帧带的是新值，整体替换").isEqualTo(42L);
    }

    @Test
    @DisplayName("队满丢帧并计数：广播路径不阻塞、不抛异常")
    void queueFullDropsFramesWithoutBlockingTheCaller() throws Exception {
        CountDownLatch blockFirstSend = new CountDownLatch(1);
        recordSends(blockFirstSend);
        LinkSessionRegistry registry = registry(1, 30_000);

        // 首帧发送被闩住 → 发送线程卡在 write(initialPayload)，队列确定性地空着，容量 1 可被观察。
        registry.register(ws);
        registry.broadcast(snapshot(2));
        registry.broadcast(snapshot(3));
        registry.broadcast(snapshot(4));

        assertThat(registry.droppedFrameCount()).as("容量 1，塞入 3 帧 → 丢 2 帧").isEqualTo(2);

        blockFirstSend.countDown();
        awaitUntil(() -> sent.size() == 2);
        assertThat(frameAt(0).isArray()).as("首帧仍是数组根类型").isTrue();
    }

    @Test
    @DisplayName("PONG 控制帧走与数据帧同一条发送线程（同会话只能有一个写者）")
    void controlFrameGoesThroughTheSameSender() throws Exception {
        recordSends(null);
        LinkSessionRegistry registry = registry(8, 30_000);
        registry.register(ws);
        awaitUntil(() -> sent.size() == 1);

        registry.sendControl("s1", "{\"action\":\"PONG\"}");

        awaitUntil(() -> sent.size() == 2);
        assertThat(sent.get(1)).isEqualTo("{\"action\":\"PONG\"}");
    }

    @Test
    @DisplayName("判据 7（链路侧同构）：某个会话的发送永久阻塞时 broadcast 立即返回，其他会话照常收到整份快照")
    void broadcastIsNonBlockingAndIsolatesASlowSession() throws Exception {
        // 慢会话：sendMessage 永不自行返回，只有注销时的 interrupt 能让发送线程退出。
        WebSocketSession slow = mock(WebSocketSession.class);
        when(slow.getId()).thenReturn("slow");
        when(slow.isOpen()).thenReturn(true);
        AtomicReference<Thread> slowSender = new AtomicReference<>();
        doAnswer(invocation -> {
            slowSender.set(Thread.currentThread());
            while (true) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }).when(slow).sendMessage(any(TextMessage.class));

        List<String> fastSent = Collections.synchronizedList(new ArrayList<>());
        WebSocketSession fast = mock(WebSocketSession.class);
        when(fast.getId()).thenReturn("fast");
        when(fast.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            fastSent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(fast).sendMessage(any(TextMessage.class));

        LinkSessionRegistry registry = registry(8, 30_000);
        registry.register(slow);
        registry.register(fast);
        awaitUntil(() -> slowSender.get() != null);
        awaitUntil(() -> fastSent.size() == 1);

        long startedAt = System.nanoTime();
        registry.broadcast(snapshot(42));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).as("投递只做 offer：慢客户端不得把链路状态迁移路径拖住").isLessThan(500);
        awaitUntil(() -> fastSent.size() == 2);
        assertThat(framesTotalOf(fastSent.get(1))).as("快会话不受慢会话影响，且拿到的是新整份快照").isEqualTo(42L);

        registry.close();
        awaitUntil(() -> !slowSender.get().isAlive());
    }

    @Test
    @DisplayName("空闲超时：服务端主动断开并注销会话，且不广播任何「我要关了」帧（设计 02 §4.8）")
    void idleSessionIsClosedAndUnregistered() throws Exception {
        recordSends(null);
        LinkSessionRegistry registry = registry(8, 30_000);
        registry.register(ws);
        awaitUntil(() -> sent.size() == 1);

        nowMs.addAndGet(30_001);
        registry.closeIdleSessions();

        assertThat(registry.activeSessionCount()).isZero();
        assertThat(sent).as("空闲断开不追加任何帧").hasSize(1);
    }

    @Test
    @DisplayName("阈值内有上行则计时重开；注销后不再投递，close() 收尾清空会话表")
    void touchKeepsSessionAliveAndCloseClearsTable() throws Exception {
        recordSends(null);
        LinkSessionRegistry registry = registry(8, 30_000);
        registry.register(ws);
        awaitUntil(() -> sent.size() == 1);

        nowMs.addAndGet(29_000);
        registry.closeIdleSessions();
        registry.touch("s1");
        nowMs.addAndGet(29_000);
        registry.closeIdleSessions();
        assertThat(registry.activeSessionCount()).as("期间有上行 → 计时重新开始").isEqualTo(1);

        registry.unregister("s1");
        assertThat(registry.activeSessionCount()).isZero();
        registry.broadcast(snapshot(2));
        assertThat(sent).as("已注销的会话不该再收到帧").hasSize(1);

        registry.register(ws);
        awaitUntil(() -> sent.size() == 2);
        registry.close();
        assertThat(registry.activeSessionCount()).isZero();
        assertThat(registry.droppedFrameCount()).as("已注销的会话不该再被计入丢帧").isZero();
    }

    @Test
    @DisplayName("构造期拒绝非正的队列容量与空闲阈值：配错要在启动时就炸，不是等到队满")
    void constructorRejectsNonPositiveLimits() {
        assertThatThrownBy(() -> registry(0, 30_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(8, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 异步断言：发送线程是虚拟线程，只能等它跑完，不能用固定 sleep 代替。 */
    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("条件未在 5s 内满足（发送线程未按预期推进）");
    }
}
