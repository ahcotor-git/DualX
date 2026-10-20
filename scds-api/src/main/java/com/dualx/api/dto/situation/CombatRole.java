package com.dualx.api.dto.situation;

/**
 * 参演单元的战斗角色（契约 situation.yaml → {@code UnitState.combatRole}）。
 *
 * <p>由马甲编号区间推导：{@code <= 1800} 为 {@link #SOLDIER}；{@code 1801~4800} 为
 * {@link #WEAPON} / {@link #VEHICLE}；{@code 65533/65534/65535} 为 {@link #HAZARD}（地雷/爆炸物/手雷）。</p>
 */
public enum CombatRole {

    /** 单兵。 */
    SOLDIER,
    /** 重武器。 */
    WEAPON,
    /** 载具。 */
    VEHICLE,
    /** 危险物（地雷/爆炸物/手雷）。 */
    HAZARD
}
