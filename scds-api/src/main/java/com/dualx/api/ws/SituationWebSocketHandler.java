package com.dualx.api.ws;

import com.dualx.core.error.BusinessException;
import com.dualx.core.push.SituationEnvelope;
import com.dualx.core.state.SituationEnvelopeFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 实时态势 WebSocket 通道（契约 paths/situation.yaml → {@code wsSituation}）。
 *
 * <p><b>双通道硬约束</b>：本通道不得替代 {@code GET /api/v1/situation}。两者推的是同一个
 * {@link SituationEnvelope}，WS 只把延迟从秒级降到毫秒级，Poll 负责兜底与恢复。</p>
 *
 * <p><b>本类只做三件事</b>（设计 [02](../../../../docs/design/02-push-ports.md) §4.5）：把握手/`RESYNC`
 * 的 {@code sinceRevision} 翻成首帧与补齐帧、交给 {@link SituationSessionRegistry}、应答保活。
 * 信封的组装在 core 的 {@link SituationEnvelopeFactory}（Poll 与 WS 共用同一份，「同构」不靠人工校准）；
 * 排队与发送在会话表（非阻塞投递，慢客户端只堵它自己）。</p>
 *
 * <p><b>超窗为什么是 close 4000 而不是 409</b>：握手成功后无法再返回 HTTP 状态码，契约为此单列了
 * close code {@code 4000} + reason；客户端收到后不带 {@code sinceRevision} 重连取全量（设计 02 §6 Q2）。</p>
 */
public class SituationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SituationWebSocketHandler.class);

    private static final String ACTION_PING = "PING";
    private static final String ACTION_RESYNC = "RESYNC";
    private static final String PONG_FRAME = "{\"action\":\"PONG\"}";

    private static final String PARAM_SINCE_REVISION = "sinceRevision";

    /**
     * 首帧携带的命中条数上限。取 {@code GET /api/v1/situation} 的契约默认值（200），使
     * 「不带 {@code sinceRevision} 的 WS 首帧」与「不带参数的 Poll」是**同一条**读侧口径（判据 1）。
     */
    public static final int DEFAULT_HITS_LIMIT = 200;

    /**
     * 超窗的关闭码与原因，与契约 {@code wsSituation} 描述逐字一致。
     *
     * <p>刻意不叫「握手拒绝」：连接已经升级成功，这里表达的是「你给的 revision 我补不了了」。</p>
     */
    private static final CloseStatus REVISION_TOO_OLD =
            new CloseStatus(4000, "SITUATION_REVISION_TOO_OLD");

    private final ObjectMapper objectMapper;
    private final SituationSessionRegistry sessions;
    private final SituationEnvelopeFactory envelopes;
    private final int hitsLimit;

    public SituationWebSocketHandler(ObjectMapper objectMapper, SituationSessionRegistry sessions,
                                     SituationEnvelopeFactory envelopes, int hitsLimit) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.hitsLimit = hitsLimit;
    }

    /**
     * 连接建立：按握手 query 的 {@code sinceRevision} 决定全量起步还是逐帧补齐。
     *
     * <p>补齐帧先于任何增量广播发出，由会话表保证（设计 02 §4.5「订阅与首帧必须原子衔接」）——
     * 这里不乱序：读完补齐帧一次性交给 {@code register}，注册发生在补齐帧入队之后。</p>
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long since = sinceRevision(session.getUri());
        if (since == null) {
            sessions.register(session, List.of(envelopes.full(hitsLimit)));
            return;
        }
        try {
            sessions.register(session, envelopes.deltaFrames(since));
        } catch (BusinessException e) {
            // 超窗：不注册（注册了立刻又要注销），按契约关掉连接，让客户端改全量重连。
            closeTooOld(session, since, e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 任何上行都算保活（契约只说「无上行」，未限定动作类型）；先记时再解析，
        // 免得一条畸形上行因为解析不出 action 而不刷新计时。
        sessions.touch(session.getId());

        String payload = message.getPayload();
        String action = readAction(payload);
        if (ACTION_PING.equals(action)) {
            // PONG 也走该会话自己的发送线程：同会话只能有一个写者（设计 02 §4.7 约定 2）。
            sessions.sendControl(session.getId(), PONG_FRAME);
            return;
        }
        if (ACTION_RESYNC.equals(action)) {
            resync(session, payload);
            return;
        }
        log.debug("态势 WS 收到未识别上行帧：id={} payload={}", session.getId(), payload);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 不在这里注销：容器随后一定会回调 afterConnectionClosed，注销只有那一个入口，
        // 否则「传输异常 + 正常关闭」会把同一个会话注销两遍。
        log.warn("态势 WS 传输异常：id={}", session.getId(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.unregister(session.getId());
        log.debug("态势 WS 通道已关闭：id={} status={}", session.getId(), status);
    }

    // ------------------------------------------------------------------ 补齐

    /**
     * {@code RESYNC}：复用握手时**同一套**补齐逻辑（设计 02 §4.5）。
     *
     * <p>唯一差别在超窗——连接还开着，不必关闭：直接回推一帧 {@code full=true}（信封自带 revision，
     * 客户端自然对齐），因此 {@code clientToServer} 的枚举保持 {@code [PING, RESYNC]}，契约不用动。</p>
     */
    private void resync(WebSocketSession session, String payload) {
        Long since = readSinceRevision(payload);
        if (since == null) {
            sessions.enqueue(session.getId(), List.of(envelopes.full(hitsLimit)));
            return;
        }
        try {
            sessions.enqueue(session.getId(), envelopes.deltaFrames(since));
        } catch (BusinessException e) {
            log.warn("态势 WS RESYNC 超窗，改推全量：id={} sinceRevision={}", session.getId(), since);
            sessions.enqueue(session.getId(), List.of(envelopes.full(hitsLimit)));
        }
    }

    private void closeTooOld(WebSocketSession session, long since, BusinessException cause) {
        log.info("态势 WS 握手补齐超窗，按契约关闭连接：id={} sinceRevision={}（{}）",
                session.getId(), since, cause.getMessage());
        try {
            session.close(REVISION_TOO_OLD);
        } catch (IOException e) {
            log.debug("态势 WS 关闭超窗连接失败（忽略）：id={}", session.getId(), e);
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 握手 query 的 {@code sinceRevision}；未带或不可解析时返回 null（契约：不带即全量）。 */
    private static Long sinceRevision(URI uri) {
        if (uri == null) {
            return null;
        }
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        return parseRevision(query.getFirst(PARAM_SINCE_REVISION));
    }

    /** 上行帧体的 {@code sinceRevision}；非数字或缺失时为 null。 */
    private Long readSinceRevision(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload).path(PARAM_SINCE_REVISION);
            return node.isNumber() ? node.asLong() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 容错解析：非 JSON 或缺少 action 时返回 null，交由调用方按「未识别」处理，不抛给容器。 */
    private String readAction(String payload) {
        try {
            return objectMapper.readTree(payload).path("action").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Long parseRevision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
