package com.dualx.core.push;

/**
 * 参演单元的战斗角色（契约 situation.yaml → {@code UnitState.combatRole}）。
 *
 * <p>由马甲编号区间推导：{@code <= 1800} 为 {@link #SOLDIER}；{@code 1801~4800} 为
 * {@link #WEAPON} / {@link #VEHICLE}；{@code 65533/65534/65535} 为 {@link #HAZARD}（地雷/爆炸物/手雷）。</p>
 *
 * <p><b>本类住中立层（{@code com.dualx.core.push}）而非 api</b>：按设计 02 §4.3 的裁定，
 * 推送载荷下沉到 {@code scds-domain}，使 core 能直接构造线载荷、api 直接复用同一类型；
 * 「HTTP Poll 与 WS 同构」由此成为类型级保证，而不是靠映射函数人工校准。</p>
 *
 * <p><b>未裁定</b>：{@code WEAPON} 与 {@code VEHICLE} 在 1801~4800 内如何划分、以及编号空隙
 * （如 2001~2200、3401~3800）归属哪一类，目前没有定则。故本枚举**不提供推导方法**，
 * 待裁定后再补（见设计 06 §6）。</p>
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
