package com.dualx.core.domain;

import com.dualx.protocol.LogType;

/**
 * 演习日志条目类型（{@code t_exercise_log.log_type}）。
 *
 * <p>为什么领域侧另立一个枚举，而不是直接用 {@code scds-protocol.LogType}：
 * 那张表要记的不只是「协议帧产生了什么」。自愈在删除残留指令之前，必须把证据
 * （指令 ID、类型、马甲、原状态、下行原文、原因）写成一条 {@link #SYSTEM} 审计记录，
 * 而 {@code SYSTEM} **不对应任何协议帧**。</p>
 *
 * <p>{@code scds-protocol.LogType} 是带字节 code（0~5）的**协议帧类型**，属于冻结边界：
 * 给它加一个没有帧的值，等于让协议库去表达一件协议里不存在的事。因此
 * <b>协议侧不动</b>，扩展的是「日志条目类型」这一层，两者用
 * {@link #of(LogType)} / {@link #protocol()} 显式映射，不靠名字巧合。</p>
 */
public enum ExerciseLogType {

    /** 演习开始（协议 ff 开始帧）。 */
    START_BATTLE,

    /** 击中（协议上报）。 */
    SHOT,

    /** 阵亡（协议上报）。 */
    DIE,

    /** 复活 / 判活（协议上报）。 */
    LIVE,

    /** 判伤（协议上报）。 */
    INJURE,

    /** 换弹（协议上报）。 */
    RELOAD,

    /** 服务端自造的系统事件（如自愈收尾存证），**无对应协议帧**。 */
    SYSTEM;

    /**
     * 协议帧类型 → 日志条目类型。用于「协议上报产生了日志」这条路径。
     *
     * @param protocol 协议帧类型，不得为 null
     */
    public static ExerciseLogType of(LogType protocol) {
        return valueOf(protocol.name());
    }

    /**
     * 日志条目类型 → 协议帧类型。
     *
     * @throws IllegalStateException {@link #SYSTEM} 没有对应帧——它本来就不是协议事件，
     *                               调用方拿到异常说明这条路走错了，而不是拿到一个假值
     */
    public LogType protocol() {
        if (this == SYSTEM) {
            throw new IllegalStateException("SYSTEM 不对应任何协议帧类型");
        }
        return LogType.valueOf(name());
    }

    /** 是否来自协议帧。{@link #SYSTEM} 恒为 false。 */
    public boolean fromProtocol() {
        return this != SYSTEM;
    }
}
