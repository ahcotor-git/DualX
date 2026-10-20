package com.dualx.api.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * 实时态势 WebSocket 通道（契约 paths/situation.yaml → {@code wsSituation}）。
 *
 * <p><b>双通道硬约束</b>：本通道不得替代 {@code GET /api/v1/situation}。两者推的是同一个
 * {@code SituationEnvelope}，WS 只把延迟从秒级降到毫秒级，Poll 负责兜底与恢复。</p>
 *
 * <p>骨架期只落地握手与保活（{@code PING}/{@code PONG}）。首帧快照与增量补齐依赖服务层维护的
 * revision 窗口，阶段 4 接入。</p>
 */
public class SituationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SituationWebSocketHandler.class);

    private static final String ACTION_PING = "PING";
    private static final String ACTION_RESYNC = "RESYNC";
    private static final String PONG_FRAME = "{\"action\":\"PONG\"}";

    private final ObjectMapper objectMapper;

    public SituationWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 骨架期不推首帧：服务端还没有 revision 源（属服务层），推空壳反而会让客户端误以为拉到了态势。
        log.debug("态势 WS 通道已建立：id={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String action = readAction(message.getPayload());
        if (ACTION_PING.equals(action)) {
            session.sendMessage(new TextMessage(PONG_FRAME));
            return;
        }
        if (ACTION_RESYNC.equals(action)) {
            log.warn("态势 WS 收到 RESYNC，但增量补齐依赖服务层 revision 窗口，阶段 4 接入：id={}", session.getId());
            return;
        }
        log.debug("态势 WS 收到未识别上行帧：id={} payload={}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("态势 WS 传输异常：id={}", session.getId(), exception);
    }

    /** 容错解析：非 JSON 或缺少 action 时返回 null，交由调用方按「未识别」处理，不抛给容器。 */
    private String readAction(String payload) {
        try {
            return objectMapper.readTree(payload).path("action").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
