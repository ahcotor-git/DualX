package com.dualx.api.ws;

import com.dualx.core.domain.LinkStatus;
import com.dualx.core.port.LinkStatusPushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 链路状态推送端口的 <b>接口适配层实现</b>（设计 [02](../../../../docs/design/02-push-ports.md) §4.2、§4.6）。
 *
 * <p>与 {@link SituationBroadcaster} 对称：网关（链路状态的唯一所有者）产生变化后调
 * {@link LinkStatusPushPort}，落到这里再扇出到 WebSocket 会话。载荷是<b>完整列表快照</b>，
 * 与 {@code GET /api/v1/devices/links} 的响应体是同一个 Java 类型
 * （{@code List<LinkStatus>}），因此两条通道在同构这一点上是类型级保证。</p>
 *
 * <p><b>为什么保持这么薄</b>：端口约定要求 {@code publish} 非阻塞、不抛异常、不得在调用线程上做 I/O。
 * 这里只做一次会话表上的 {@code offer}（队列与发送线程在 {@link LinkSessionRegistry} 里），
 * 于是网关的链路迁移线程（可能是串口读线程、Netty worker 或 HTTP 线程）不会被慢客户端拖住。</p>
 *
 * <p><b>它同时是「本进程有 WS 消费者」的信号</b>：{@code GatewayConfig} 用
 * {@code ObjectProvider.getIfAvailable} 取端口，本 bean 存在即被选中，缺席时注册表只更新不广播。</p>
 */
public class LinkStatusBroadcaster implements LinkStatusPushPort {

    private static final Logger log = LoggerFactory.getLogger(LinkStatusBroadcaster.class);

    private final LinkSessionRegistry sessions;

    public LinkStatusBroadcaster(LinkSessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public void publish(List<LinkStatus> snapshot) {
        try {
            sessions.broadcast(snapshot);
        } catch (RuntimeException e) {
            // 端口约定：实现方内部异常一律自己吞掉。丢一帧不该让链路状态机看到异常。
            log.warn("链路状态广播失败（已忽略，不影响上游）：{}", e.toString());
        }
    }
}
