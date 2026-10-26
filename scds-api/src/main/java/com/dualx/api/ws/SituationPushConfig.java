package com.dualx.api.ws;

import com.dualx.core.state.SituationEnvelopeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 态势推送的 Spring 装配（设计 [02](../../../../docs/design/02-push-ports.md) §4.6、§4.7、§5）。
 *
 * <p><b>为什么不把这两个 bean 写在 {@link WebSocketConfig} 里</b>：{@link SituationWebSocketHandler}
 * 由 {@code WebSocketConfig} 构造、因而需要 {@code registry}；若 {@code registry} 也声明在同一个
 * {@code @Configuration} 类里，就成了「创建 {@code webSocketConfig} 需要 registry，而 registry 的
 * {@code @Bean} 方法又必须由 {@code webSocketConfig} 实例承载」的自引用，Spring 会在
 * {@code webSocketConfig} 上报 {@code BeanCurrentlyInCreationException}。拆到本类后依赖仍是单向的
 * {@code WebSocketConfig → registry ← SituationBroadcaster}（设计 02 §4.6），URL 装配点也仍只在
 * {@link WebSocketConfig} 一处。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SituationPushConfig {

    private static final Logger log = LoggerFactory.getLogger(SituationPushConfig.class);

    /**
     * 态势会话表：每会话一条有界队列 + 一个虚拟线程（设计 02 §4.7）。
     *
     * <p><b>为什么全量来源用 {@link ObjectProvider} 而不是直接注入工厂</b>：本 bean 会在 core 装配
     * {@code SituationStateService} 的过程中被创建（状态机 → 推送端口 → 广播器 → 本表），而工厂 →
     * {@code SituationQuery} → 状态机，直接注入等于把「正在创建的状态机」再要一遍，成环。
     * 改成运行时按需取工厂，环就断在 {@code Supplier} 这一层（{@code SituationSessionRegistry}
     * 的构造参数说明写的是同一件事）。</p>
     *
     * <p>容量与阈值来自 {@code scds.push.*}：容量是**候选值**，不是容量结论——现场客户端数到位前
     * 不得据其宣称容量达标（设计 02 §6 Q5，D10）。</p>
     */
    @Bean
    public SituationSessionRegistry situationSessionRegistry(
            ObjectMapper objectMapper,
            ObjectProvider<SituationEnvelopeFactory> envelopeFactory,
            @Value("${scds.push.send-queue-capacity:256}") int queueCapacity,
            @Value("${scds.push.idle-timeout-ms:30000}") long idleTimeoutMs) {
        log.info("态势 WS 会话表就绪：待发队列容量={}，空闲断开阈值={}ms（容量为 D10 候选值，非生产结论）",
                queueCapacity, idleTimeoutMs);
        return new SituationSessionRegistry(objectMapper, queueCapacity, idleTimeoutMs,
                System::currentTimeMillis,
                () -> envelopeFactory.getObject().full(SituationWebSocketHandler.DEFAULT_HITS_LIMIT));
    }

    /**
     * 推送端口的 api 侧实现：它的存在本身就是「本进程有 WS 消费者」的信号，
     * core 据此不再退回 {@code NoopSituationPushPort}（设计 02 §5 / §6 Q6）。
     */
    @Bean
    public SituationBroadcaster situationBroadcaster(SituationSessionRegistry situationSessions) {
        return new SituationBroadcaster(situationSessions);
    }
}
