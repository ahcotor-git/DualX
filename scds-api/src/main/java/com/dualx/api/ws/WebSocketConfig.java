package com.dualx.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配：两条推送通道的 URL 与处理器绑定，全系统只在这里出现一次。
 *
 * <p>URL 与契约一致：{@code /ws/v1/situation}（态势）、{@code /ws/v1/devices/links}（链路），
 * 端口仍是硬约束的 11001。</p>
 *
 * <p>处理器不标 {@code @Component} 而是由本类构造：切片测试（{@code @WebMvcTest}）只装配
 * {@code WebMvcConfigurer} 一族，若处理器是独立 Bean，切片里会出现「有配置无依赖」的启动失败。
 * 收在这里也顺带保证了「WS 只在装配处出现一次」。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SituationWebSocketHandler situationHandler;
    private final LinkWebSocketHandler linkHandler;

    public WebSocketConfig(ObjectMapper objectMapper) {
        this.situationHandler = new SituationWebSocketHandler(objectMapper);
        this.linkHandler = new LinkWebSocketHandler(objectMapper);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 现场客户端（Flutter 桌面 / 安卓）不发 Origin 头，需放开来源校验；
        // 契约声明的子协议（scds.situation.v1 / scds.links.v1）待阶段 4 接入真实推送时再协商。
        registry.addHandler(situationHandler, "/ws/v1/situation").setAllowedOriginPatterns("*");
        registry.addHandler(linkHandler, "/ws/v1/devices/links").setAllowedOriginPatterns("*");
    }
}
