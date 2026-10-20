package com.dualx.core.domain;

/**
 * 运动状态（契约 {@code MotionState}）。
 *
 * <p><b>硬约束</b>：人员阵亡后服务端必须把坐标冻结在最后有效位置且绝对静止，
 * 此时为 {@link #FROZEN} 且坐标不再随新上报变化；离线<b>不会</b>解除冻结。
 */
public enum MotionState {

    /** 移动中。 */
    MOVING,
    /** 静止（存活但未移动）。 */
    STATIONARY,
    /** 冻结：阵亡后停在最后有效位置。 */
    FROZEN
}
