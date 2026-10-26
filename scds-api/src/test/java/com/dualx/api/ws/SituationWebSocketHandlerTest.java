package com.dualx.api.ws;

import com.dualx.core.push.UnitState;
import com.dualx.core.state.SituationEnvelopeFactory;
import com.dualx.core.state.SituationQuery;
import com.dualx.protocol.VestStatus;
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

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 态势 WS 通道的首帧 / 补齐 / 超窗行为（设计 [02](../../../../../docs/design/02-push-ports.md) §4.5，
 * 验收判据 1/2/3）。
 *
 * <p><b>为什么用真工厂、假读侧</b>：{@link SituationEnvelopeFactory} 是「WS 与 Poll 同构」的落点，
 * 它逐 revision 分帧、超窗抛 {@code SITUATION_REVISION_TOO_OLD} 的语义正是本类要证明的东西，
 * 用替身替掉就等于自证。故只把 {@link SituationQuery}（读侧原语）换成替身，
 * 工厂与会话表都用真件——本类证明的是「装配起来之后，一条握手 query 换回哪几帧」。</p>
 */
class SituationWebSocketHandlerTest {

    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");
    private static final String WS_BASE = "ws://127.0.0.1:11001/ws/v1/situation";

    /** 服务端当前 revision；补齐帧的编号都相对它而言（判据 2 的「当前 revision − 3」）。 */
    private static final long CURRENT_REVISION = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 发出的全部帧体（顺序即发送顺序）。 */
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    private final AtomicLong nowMs = new AtomicLong(1_000_000L);

    private SituationQuery query;
    private SituationEnvelopeFactory envelopes;
    private SituationSessionRegistry sessions;
    private SituationWebSocketHandler handler;
    private WebSocketSession ws;

    @BeforeEach
    void setUp() throws Exception {
        sent.clear();
        nowMs.set(1_000_000L);

        query = mock(SituationQuery.class);
        when(query.epoch()).thenReturn("epoch-1");
        when(query.revision()).thenReturn(CURRENT_REVISION);
        when(query.running()).thenReturn(true);
        when(query.snapshot()).thenReturn(List.of());
        when(query.teamSummary()).thenReturn(List.of());
        envelopes = new SituationEnvelopeFactory(query, Clock.fixed(T0, ZoneOffset.UTC));

        sessions = new SituationSessionRegistry(objectMapper, 16, 30_000, nowMs::get,
                () -> envelopes.full(SituationWebSocketHandler.DEFAULT_HITS_LIMIT));
        handler = new SituationWebSocketHandler(objectMapper, sessions, envelopes,
                SituationWebSocketHandler.DEFAULT_HITS_LIMIT);

        ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("s1");
        when(ws.isOpen()).thenReturn(true);
        recordSends();
    }

    @Test
    @DisplayName("不带 sinceRevision 的握手：首帧是 full=true 全量（与不带参数的 Poll 同一条读侧口径）")
    void handshakeWithoutSinceRevisionPushesFullSnapshot() throws Exception {
        when(ws.getUri()).thenReturn(URI.create(WS_BASE));

        handler.afterConnectionEstablished(ws);

        awaitUntil(() -> sent.size() == 1);
        JsonNode frame = objectMapper.readTree(sent.get(0));
        assertThat(frame.path("full").asBoolean()).isTrue();
        assertThat(frame.path("revision").asLong()).isEqualTo(CURRENT_REVISION);
        assertThat(frame.path("epoch").asText()).as("信封带 epoch：客户端据此判断服务端是否换代")
                .isEqualTo("epoch-1");
        assertThat(frame.path("units").size()).as("无演习时 units 为空数组").isZero();
        assertThat(sessions.activeSessionCount()).isEqualTo(1);
        verify(ws, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("带 sinceRevision 的握手：从 N+1 起逐 revision 推 full=false，编号连续无跳号（判据 2）")
    void handshakeWithSinceRevisionPushesOneFramePerRevision() throws Exception {
        when(ws.getUri()).thenReturn(URI.create(WS_BASE + "?sinceRevision=3"));
        when(query.changesSince(3L)).thenReturn(Optional.of(List.of(unit(11, 4L), unit(12, 5L), unit(11, 6L))));

        handler.afterConnectionEstablished(ws);

        awaitUntil(() -> sent.size() == 3);
        assertThat(revisions()).as("每帧推进一格，客户端侧「补齐到哪了」可见").containsExactly(4L, 5L, 6L);
        assertThat(sent).allSatisfy(payload -> assertThat(payload).contains("\"full\":false"));
        assertThat(sessions.activeSessionCount()).isEqualTo(1);
        verify(ws, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("带超窗 sinceRevision 的握手：close 4000 + 契约 reason，且不注册会话（判据 3）")
    void handshakeWithStaleRevisionClosesWith4000() throws Exception {
        when(ws.getUri()).thenReturn(URI.create(WS_BASE + "?sinceRevision=0"));
        when(query.changesSince(0L)).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(ws);

        verify(ws).close(org.mockito.ArgumentMatchers.argThat(status -> status != null
                && status.getCode() == 4000
                && "SITUATION_REVISION_TOO_OLD".equals(status.getReason())));
        assertThat(sessions.activeSessionCount()).isZero();
        assertThat(sent).as("超窗不发任何帧：客户端应不带 sinceRevision 重连取全量").isEmpty();
    }

    @Test
    @DisplayName("PING 回 PONG，且由该会话的发送线程写出（同会话唯一写者）")
    void pingIsAnsweredByTheSessionSendThread() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);

        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"PING\"}"));

        awaitUntil(() -> sent.size() == 2);
        assertThat(sent.get(1)).isEqualTo("{\"action\":\"PONG\"}");
    }

    @Test
    @DisplayName("RESYNC 超窗：不关连接，直接回推一帧 full=true（契约的 RESYNC 分支）")
    void resyncWithStaleRevisionPushesFullFrame() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);
        when(query.changesSince(0L)).thenReturn(Optional.empty());

        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"RESYNC\",\"sinceRevision\":0}"));

        awaitUntil(() -> sent.size() == 2);
        JsonNode frame = objectMapper.readTree(sent.get(1));
        assertThat(frame.path("full").asBoolean()).isTrue();
        assertThat(frame.path("revision").asLong()).isEqualTo(CURRENT_REVISION);
        verify(ws, never()).close(any(CloseStatus.class));
        assertThat(sessions.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("任何上行都刷新保活计时（契约只说「无上行」，未限定动作类型）")
    void anyInboundMessageKeepsSessionAlive() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);

        nowMs.addAndGet(29_000);
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"RESYNC\"}"));
        nowMs.addAndGet(29_000);
        sessions.closeIdleSessions();

        assertThat(sessions.activeSessionCount()).as("期间有上行 → 计时重新开始").isEqualTo(1);
    }

    @Test
    @DisplayName("连接关闭后会话从表中注销，活跃数回落到真实值")
    void closingConnectionUnregistersSession() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        assertThat(sessions.activeSessionCount()).isZero();
    }

    // ------------------------------------------------------------------ 辅助

    /** 记录发出的每一帧（发送线程是虚拟线程，断言一律用 awaitUntil 等它推进）。 */
    private void recordSends() throws Exception {
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));
    }

    private List<Long> revisions() {
        return sent.stream().map(this::revisionOf).toList();
    }

    private long revisionOf(String payload) {
        try {
            return objectMapper.readTree(payload).path("revision").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("帧体不是合法 JSON：" + payload, e);
        }
    }

    /** 只填本条用例关心的字段：单元级变化的其余内容由 {@code SituationEnvelopeFactoryTest} 覆盖。 */
    private static UnitState unit(int vestNum, long revision) {
        return new UnitState(vestNum, null, null, null, null, VestStatus.LIVE,
                100, 30, 30, null, null, null, null, T0, null, revision);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("条件未在 5s 内满足（发送线程未按预期推进）");
    }
}
