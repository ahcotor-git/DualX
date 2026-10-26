package com.dualx.api.ws;

import com.dualx.core.push.SituationEnvelope;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 态势 WS 会话表的行为（设计 [02](../../../../../docs/design/02-push-ports.md) §4.7、§4.8）。
 *
 * <p>纯单测：会话用 Mockito 替身，时钟用可控毫秒来源，不启 Spring、不占端口。这里证明的正是
 * 「只有装配起来才看得见」的三件事：首帧先于增量、队满丢帧后自动收敛为全量、空闲会被注销。</p>
 */
class SituationSessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");

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

    private SituationSessionRegistry registry(int capacity, long idleTimeoutMs) {
        return new SituationSessionRegistry(objectMapper, capacity, idleTimeoutMs, nowMs::get, () -> full(999));
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

    private List<Long> sentRevisions() {
        return sent.stream().map(this::revisionOf).toList();
    }

    private long revisionOf(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root.path("revision").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("帧体不是合法 JSON：" + payload, e);
        }
    }

    private static SituationEnvelope full(long revision) {
        return new SituationEnvelope("epoch-1", revision, true, true, null, T0,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static SituationEnvelope delta(long revision) {
        return new SituationEnvelope("epoch-1", revision, false, true, null, T0,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("首帧（补齐帧）先于并发广播的增量帧发出，控制帧走同一条发送线程")
    void initialFramesAreSentBeforeBroadcastFrames() throws Exception {
        recordSends(null);
        SituationSessionRegistry registry = registry(8, 30_000);

        registry.register(ws, List.of(full(1)));
        registry.broadcast(delta(2));
        registry.sendControl("s1", "{\"action\":\"PONG\"}");

        awaitUntil(() -> sent.size() == 3);
        assertThat(revisionOf(sent.get(0))).as("首帧是全量（revision=1）").isEqualTo(1L);
        assertThat(revisionOf(sent.get(1))).as("随后才是增量（revision=2）").isEqualTo(2L);
        assertThat(sent.get(0)).as("首帧必须是 full=true").contains("\"full\":true");
        assertThat(sent.get(1)).as("增量帧不得带 full=true").contains("\"full\":false");
        assertThat(sent.get(2)).as("PONG 也由发送线程写").isEqualTo("{\"action\":\"PONG\"}");
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("队满丢帧并计数；后续 revision 不连续时改推全量（客户端凭它直接对齐）")
    void droppedFrameConvergesToFullFrame() throws Exception {
        CountDownLatch blockFirstSend = new CountDownLatch(1);
        recordSends(blockFirstSend);
        SituationSessionRegistry registry = registry(1, 30_000);

        // 首帧发送被闩住 → 队列空着，随后的广播能确定性地观察到「容量 1」。
        registry.register(ws, List.of(full(1)));
        registry.broadcast(delta(2));
        registry.broadcast(delta(3));
        registry.broadcast(delta(4));
        assertThat(registry.droppedFrameCount()).as("容量 1，塞入 3 帧 → 丢 2 帧").isEqualTo(2);

        blockFirstSend.countDown();
        awaitUntil(() -> sent.size() == 2);
        assertThat(sentRevisions()).as("留存的那帧与首帧相邻，照常推增量").containsExactly(1L, 2L);

        // 丢帧后**下一帧**才看得出跳号（2 → 5）：此时不推它，改推一帧全量。
        registry.broadcast(delta(5));
        awaitUntil(() -> sent.size() == 3);
        assertThat(sentRevisions()).containsExactly(1L, 2L, 999L);
        assertThat(registry.resyncCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("空闲超时：服务端主动断开并注销会话，活跃数回到真实值")
    void idleSessionIsClosedAndUnregistered() throws Exception {
        recordSends(null);
        SituationSessionRegistry registry = registry(8, 30_000);
        registry.register(ws, List.of(full(1)));
        awaitUntil(() -> sent.size() == 1);

        nowMs.addAndGet(30_001);
        registry.closeIdleSessions();

        assertThat(registry.activeSessionCount()).isZero();
        assertThat(sent).as("空闲断开不广播任何「我要关了」帧（设计 02 §4.8）").hasSize(1);
        verifyClose(CloseStatus.NORMAL.getCode());
    }

    @Test
    @DisplayName("会话表内会话在空闲阈值内不被断开（否则 30s 之前就断了）")
    void freshSessionSurvivesIdleScan() throws Exception {
        recordSends(null);
        SituationSessionRegistry registry = registry(8, 30_000);
        registry.register(ws, List.of(full(1)));
        awaitUntil(() -> sent.size() == 1);

        nowMs.addAndGet(29_000);
        registry.closeIdleSessions();
        registry.touch("s1");
        nowMs.addAndGet(29_000);
        registry.closeIdleSessions();

        assertThat(registry.activeSessionCount()).as("期间有上行 → 计时重新开始").isEqualTo(1);
    }

    @Test
    @DisplayName("注销后不再投递；close() 收尾清空会话表（不泄漏发送线程）")
    void unregisterStopsDeliveryAndCloseClearsTable() throws Exception {
        recordSends(null);
        SituationSessionRegistry registry = registry(8, 30_000);
        registry.register(ws, List.of(full(1)));
        awaitUntil(() -> sent.size() == 1);

        registry.unregister("s1");
        assertThat(registry.activeSessionCount()).isZero();
        registry.broadcast(delta(2));
        registry.sendControl("s1", "{\"action\":\"PONG\"}");
        registry.register(ws, List.of(full(1)));
        awaitUntil(() -> sent.size() == 2);

        registry.close();
        assertThat(registry.activeSessionCount()).isZero();
        assertThat(registry.droppedFrameCount()).as("已注销的会话不该再被计入丢帧").isZero();
    }

    private void verifyClose(int code) throws java.io.IOException {
        org.mockito.Mockito.verify(ws).close(org.mockito.ArgumentMatchers.argThat(
                status -> status != null && status.getCode() == code));
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
