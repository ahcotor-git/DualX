package com.dualx.api.ws;

import com.dualx.core.port.SituationPushPort;
import com.dualx.core.push.SituationEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 态势推送端口的 <b>接口适配层实现</b>（设计 [02](../../../../docs/design/02-push-ports.md) §4.2、§4.6）。
 *
 * <p>它是「第三条边」的 api 侧端点：core 产生态势变化后调 {@link SituationPushPort}，落到这里再扇出到
 * WebSocket 会话。<b>名字刻意叫 Broadcaster 而不是 Publisher</b>——它不是状态持有者，只是把已经组装好的
 * 信封发给各会话的适配器；状态与 revision 的唯一所有者仍是 core 的 {@code SituationStateService}（设计 02 §4.2）。</p>
 *
 * <p><b>为什么保持这么薄</b>：端口约定要求 {@code publish} 非阻塞、不抛异常、且不得在调用线程上做 I/O。
 * 这里只做一次 {@code ConcurrentHashMap} 上的 {@code offer}（{@code SituationSessionRegistry} 负责队列与发送线程），
 * 于是 core 的事件处理线程不会被任何慢客户端拖住。</p>
 *
 * <p><b>它同时是「本进程有 WS 消费者」的信号</b>：core 用 {@code ObjectProvider.getIfAvailable} 取端口，
 * 本 bean 存在即被选中，否则退回 {@code NoopSituationPushPort}（设计 02 §5 / §6 Q6）。</p>
 */
public class SituationBroadcaster implements SituationPushPort {

    private static final Logger log = LoggerFactory.getLogger(SituationBroadcaster.class);

    private final SituationSessionRegistry sessions;

    public SituationBroadcaster(SituationSessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public void publish(SituationEnvelope envelope) {
        try {
            sessions.broadcast(envelope);
        } catch (RuntimeException e) {
            // 端口约定：实现方内部异常一律自己吞掉。丢一帧不该让 core 的事件循环看到异常。
            log.warn("态势广播失败（已忽略，不影响上游）：revision={}", envelope.revision(), e);
        }
    }
}
