package com.dualx.core.state;

import com.dualx.core.port.SituationPushPort;
import com.dualx.core.push.SituationEnvelope;

/**
 * 无推送适配：核心在没有 WebSocket 实现时的默认端口实现
 * （[设计 02](../../../../docs/design/02-push-ports.md) §5、§6 Q6）。
 *
 * <p><b>为什么需要它</b>：态势写侧是无条件推送的（{@code beginExercise} / {@code endExercise} /
 * 每次单元变化各推一帧）。若没有这个兜底实现，核心单测与「只要核心不要 WS」的切片就<b>被迫</b>
 * 拉起 WebSocket 上下文才能构造 {@code SituationStateService}，切片测试的成本会立刻反噬。</p>
 *
 * <p><b>刻意不记日志</b>：它的存在只说明「本进程没有 WS 消费者」，不是异常、也不是丢帧——
 * 契约本身要求客户端即便在 WS 已连接时也持续低频 Poll，因此没有 WS 并不意味着拿不到态势。</p>
 */
public final class NoopSituationPushPort implements SituationPushPort {

    @Override
    public void publish(SituationEnvelope envelope) {
        // 丢弃：本进程没有下行消费者。
    }
}
