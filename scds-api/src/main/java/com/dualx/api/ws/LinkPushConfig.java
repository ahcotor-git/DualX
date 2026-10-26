package com.dualx.api.ws;

import com.dualx.core.port.DeviceLinkAdmin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 链路状态推送的 Spring 装配（设计 [02](../../../../docs/design/02-push-ports.md) §4.6、§4.7、§5）。
 *
 * <p><b>为什么不把这两个 bean 写在 {@link WebSocketConfig} 里</b>：与
 * {@link SituationPushConfig} 同一个理由——{@link LinkWebSocketHandler} 由 {@code WebSocketConfig}
 * 构造、因而需要 {@code registry}；若 {@code registry} 也声明在同一个 {@code @Configuration} 类里，
 * 就成了「创建 {@code webSocketConfig} 需要 registry，而 registry 的 {@code @Bean} 方法又必须由
 * {@code webSocketConfig} 实例承载」的自引用，Spring 会在 {@code webSocketConfig} 上报
 * {@code BeanCurrentlyInCreationException}。URL 装配点仍只在 {@link WebSocketConfig} 一处。</p>
 *
 * <p><b>为什么快照来源用 {@link ObjectProvider} 而不是直接注入 {@link DeviceLinkAdmin}</b>：
 * 链路注册表（网关侧）持有推送端口，而端口实现（本类装配的 {@code LinkStatusBroadcaster}）
 * 又要读注册表——直接注入会把环闭死（注册表 → 端口 → 广播器 → 会话表 → 注册表）。
 * 改成运行时按需取端口，环就断在 {@code Supplier} 这一层；调用发生在握手与链路变化时，
 * 那时应用上下文早已就绪。</p>
 */
@Configuration(proxyBeanMethods = false)
public class LinkPushConfig {

    private static final Logger log = LoggerFactory.getLogger(LinkPushConfig.class);

    /**
     * 链路会话表：每会话一条有界队列 + 一个虚拟线程（设计 02 §4.7）。
     *
     * <p>容量与阈值复用 {@code scds.push.*}：与态势通道同一个语义——容量是<b>候选值</b>，
     * 不是容量结论，现场客户端数（设计 02 §6 Q5，D10）到位前不得据其宣称容量达标。</p>
     */
    @Bean
    public LinkSessionRegistry linkSessionRegistry(
            ObjectMapper objectMapper,
            ObjectProvider<DeviceLinkAdmin> links,
            @Value("${scds.push.send-queue-capacity:256}") int queueCapacity,
            @Value("${scds.push.idle-timeout-ms:30000}") long idleTimeoutMs) {
        log.info("链路 WS 会话表就绪：待发队列容量={}，空闲断开阈值={}ms（容量为 D10 候选值，非生产结论）",
                queueCapacity, idleTimeoutMs);
        return new LinkSessionRegistry(objectMapper, queueCapacity, idleTimeoutMs,
                System::currentTimeMillis,
                () -> links.getObject().snapshot());
    }

    /**
     * 推送端口的 api 侧实现：它的存在本身就是「本进程有 WS 消费者」的信号，
     * {@code GatewayConfig} 据此把注册表接上扇出（设计 02 §5）。
     */
    @Bean
    public LinkStatusBroadcaster linkStatusBroadcaster(LinkSessionRegistry linkSessions) {
        return new LinkStatusBroadcaster(linkSessions);
    }
}
