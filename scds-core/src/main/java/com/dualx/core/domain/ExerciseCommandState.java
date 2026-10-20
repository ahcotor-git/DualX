package com.dualx.core.domain;

/**
 * 下行指令队列状态（{@code t_exercise_command.state}）。
 *
 * <p>与 {@code CommandAck.accepted} 的分工：{@code accepted} 是「有没有进队」的即时答复，
 * 本枚举是进队之后的流转轨迹。契约里马甲离线时指令要挂起等待重连，因此
 * {@link #PENDING} 是一个会被持久化的合法状态，而不是转瞬即逝的中间态。
 */
public enum ExerciseCommandState {

    /** 待发：已入队，尚未下发（马甲可能离线）。 */
    PENDING,
    /** 已发未确认：字节已写往链路，等待马甲回执。 */
    SENT,
    /** 已确认：马甲回执到达。 */
    ACKED,
    /** 下发失败（链路异常 / 组帧失败等）。 */
    FAILED,
    /** 超时作废：长时间未获回执，放弃等待。 */
    EXPIRED
}
