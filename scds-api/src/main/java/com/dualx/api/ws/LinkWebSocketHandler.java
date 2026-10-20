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
 * 链路状态 WebSocket 通道（契约 paths/device.yaml → {@code wsLinks}）。
 *
 * <p>替代旧系统 {@code /socket/in} 与 {@code /socket/out} 两个端点。同样遵循双通道约定：
 * 不得替代 {@code GET /api/v1/devices/links}。载荷类型与 Poll 完全一致（{@code LinkStatus}）。</p>
 *
 * <p>骨架期只落地握手与保活；链路状态变化事件由网关层（scds-gateway）产生，阶段 4 接入。</p>
 */
public class LinkWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LinkWebSocketHandler.class);

    private static final String ACTION_PING = "PING";
    private static final String PONG_FRAME = "{\"action\":\"PONG\"}";

    private final ObjectMapper objectMapper;

    public LinkWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 骨架期不推首帧：链路状态源在网关层，阶段 4 接入后在连接建立时推当前 LinkStatus。
        log.debug("链路 WS 通道已建立：id={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        // 契约 clientToServer 只定义了 PING，无业务上行。
        if (ACTION_PING.equals(readAction(message.getPayload()))) {
            session.sendMessage(new TextMessage(PONG_FRAME));
            return;
        }
        log.debug("链路 WS 收到未识别上行帧：id={} payload={}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("链路 WS 传输异常：id={}", session.getId(), exception);
    }

    private String readAction(String payload) {
        try {
            return objectMapper.readTree(payload).path("action").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
