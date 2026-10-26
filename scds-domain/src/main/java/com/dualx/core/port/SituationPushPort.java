package com.dualx.core.port;

import com.dualx.core.push.SituationEnvelope;

/**
 * 态势**推送**端口（设计 02 §4.1 的「第三条边」）：核心侧产生态势变化时调用，
 * 由接口适配层（{@code scds-api} 的 {@code SituationBroadcaster} → WebSocket）广播给客户端。
 *
 * <p>与 {@link HardwareEventSink} 对称：端口定义在中立的 {@code scds-domain}，
 * 核心（{@code scds-core}）是调用方、接口适配层是实现方。这样 core 不必反向依赖 {@code scds-api}，
 * 依赖链仍为 {@code scds-api → scds-core → scds-domain}，无环。</p>
 *
 * <p><b>实现方约定</b>：本方法在核心侧的事件处理线程上被直接调用，必须
 * <b>非阻塞、不得抛异常</b>；任何 I/O 等待（慢客户端、背压）都要下沉到实现方自己的发送线程与
 * 有界队列里，不得阻塞上游事件循环。</p>
 *
 * <p>载荷即 {@link SituationEnvelope}——与 HTTP Poll 响应体是同一个 Java 类型，全量/增量由信封的
 * {@code full} 决定，本端口不做任何映射。</p>
 */
public interface SituationPushPort {

    /**
     * 广播一帧态势信封（全量或增量由信封的 {@code full} 决定）。
     *
     * @param envelope 与 Poll 响应体同构的信封；调用方保证非 null
     */
    void publish(SituationEnvelope envelope);
}
