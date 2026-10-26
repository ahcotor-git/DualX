package com.dualx.api.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 链路状态 WebSocket 通道（契约 paths/device.yaml → {@code wsLinks}）。
 *
 * <p>替代旧系统 {@code /socket/in} 与 {@code /socket/out} 两个端点。同样遵循双通道约定：
 * 不得替代 {@code GET /api/v1/devices/links}。</p>
 *
 * <p><b>载荷与 Poll 同构</b>：握手后的首帧、以及每次链路状态变化推的都是<b>完整列表快照</b>
 * （{@code LinkStatus} 数组，三种链路恒定各一条），与 HTTP {@code getLinks} 的响应体是同一个
 * Java 类型。客户端在两条通道上因此共用同一套「整体替换」逻辑，不需要合并单条增量。</p>
 *
 * <p><b>本类只做协议搬运</b>：首帧、队列、发送线程、保活与停机全在
 * {@link LinkSessionRegistry}；{@code PING} 的应答也走会话表的发送线程，而不是在容器读线程上
 * 直接写——同会话只能有一个写者（设计 02 §4.7）。链路状态变化本身由网关产生并经
 * {@code LinkStatusBroadcaster} 扇出。</p>
 */
public class LinkWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LinkWebSocketHandler.class);

    private static final String ACTION_PING = "PING";
    private static final String PONG_FRAME = "{\"action\":\"PONG\"}";

    private final ObjectMapper objectMapper;
    private final LinkSessionRegistry sessions;

    public LinkWebSocketHandler(ObjectMapper objectMapper, LinkSessionRegistry sessions) {
        this.objectMapper = objectMapper;
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.register(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 任何上行都算保活（契约与态势侧同为「服务端 30s 无上行主动断开」），随后才看是不是 PING。
        sessions.touch(session.getId());
        if (ACTION_PING.equals(readAction(message.getPayload()))) {
            sessions.sendControl(session.getId(), PONG_FRAME);
            return;
        }
        log.debug("链路 WS 收到未识别上行帧：id={} payload={}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 只打日志、不注销：注销只保留 afterConnectionClosed 一个入口，避免双重注销。
        log.warn("链路 WS 传输异常：id={}", session.getId(), exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.unregister(session.getId());
    }

    private String readAction(String payload) {
        try {
            return objectMapper.readTree(payload).path("action").asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
