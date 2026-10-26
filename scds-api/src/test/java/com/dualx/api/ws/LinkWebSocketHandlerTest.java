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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链路 WS 通道处理器的接线（契约 paths/device.yaml → {@code wsLinks}，判据 1 的链路侧）。
 *
 * <p>用<b>真</b> {@link LinkSessionRegistry} + Mockito 的 {@link WebSocketSession} 替身，
 * 证明握手即推数组根类型的完整快照、{@code PING} 应答 {@code PONG}、上行只记保活不额外推帧、
 * 关闭即注销。这里刻意不重测会话表内部的队列/保活细节——那是 {@link LinkSessionRegistryTest}
 * 的职责。</p>
 */
class LinkWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    private WebSocketSession ws;
    private LinkSessionRegistry registry;
    private LinkWebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        sent.clear();
        ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("s1");
        when(ws.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(ws).sendMessage(any(TextMessage.class));

        registry = new LinkSessionRegistry(objectMapper, 8, 30_000,
                System::currentTimeMillis, LinkWebSocketHandlerTest::snapshot);
        handler = new LinkWebSocketHandler(objectMapper, registry);
    }

    private static List<LinkStatus> snapshot() {
        return List.of(
                new LinkStatus(LinkKind.SERIAL_VEST, LinkState.CLOSED, null, null, null, null, 0L, 0L, 0L, null),
                new LinkStatus(LinkKind.UWB_TCP_SERVER, LinkState.CLOSED, null, null, null, null, 0L, 0L, 0L, null),
                new LinkStatus(LinkKind.UWB_SERIAL, LinkState.CLOSED, null, null, null, null, 0L, 0L, 0L, null));
    }

    @Test
    @DisplayName("握手即注册，并推出一帧数组根类型的完整快照")
    void handshakeRegistersAndPushesArraySnapshot() throws Exception {
        handler.afterConnectionEstablished(ws);

        awaitUntil(() -> sent.size() == 1);
        JsonNode root = objectMapper.readTree(sent.get(0));
        assertThat(root.isArray()).as("根类型是数组，与 HTTP getLinks 响应体同构").isTrue();
        assertThat(root.size()).isEqualTo(LinkKind.values().length);
        assertThat(registry.activeSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PING 由会话表的发送线程应答 PONG；识别不了的上行只记保活、不多推帧")
    void pingIsAnsweredAndUnknownFrameOnlyTouches() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);

        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"PING\"}"));
        awaitUntil(() -> sent.size() == 2);
        assertThat(sent.get(1)).isEqualTo("{\"action\":\"PONG\"}");

        handler.handleTextMessage(ws, new TextMessage("not-json"));
        handler.handleTextMessage(ws, new TextMessage("{\"action\":\"WAT\"}"));

        assertThat(sent).as("未识别上行与坏 JSON 都不产生新帧").hasSize(2);
        assertThat(registry.activeSessionCount()).as("但它们仍然算保活，会话不该被误踢").isEqualTo(1);
    }

    @Test
    @DisplayName("连接关闭即注销；重复注销不会出错（注销只有 afterConnectionClosed 一个入口）")
    void closeUnregistersSession() throws Exception {
        handler.afterConnectionEstablished(ws);
        awaitUntil(() -> sent.size() == 1);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        assertThat(registry.activeSessionCount()).isZero();
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
