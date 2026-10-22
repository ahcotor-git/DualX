package com.dualx.core.domain;

/**
 * 演习结束原因（{@code t_exercise.end_reason}）。
 *
 * <p>把「这一场是怎么结束的」显式落库，现场才能区分「操作员按下停演」与
 * 「进程异常退出后由启动自愈收尾」——否则复盘列表里两者长得一模一样。</p>
 *
 * <p>取值的克制是有意的：当前没有任何数据源能区分出比这两者更细的现场语义
 * （例如「链路断开导致的终止」与「断电」在服务端看到的事实完全相同），
 * 多造一个枚举值就等于要求实现去猜。</p>
 *
 * <p>进行中的场次 {@code end_reason} 为 {@code null}，因此本枚举没有
 * {@code RUNNING} 之类的值——「进行中」由 {@code ended_at IS NULL} 表达。</p>
 */
public enum ExerciseEndReason {

    /** 正常停演：有人（或指挥台）主动结束了这一场。 */
    NORMAL,

    /** 异常中断：进程崩溃/断电等导致 {@code ended_at} 未写入，由启动自愈补写收尾。 */
    CRASH_RECOVERED
}
