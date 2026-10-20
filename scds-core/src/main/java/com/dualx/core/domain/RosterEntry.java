package com.dualx.core.domain;

import com.dualx.protocol.Team;

import java.time.Instant;
import java.util.UUID;

/**
 * 想定编成（{@code t_plan_roster}），逐人一行，契约 {@code RosterEntry}。
 *
 * <p>旧库有两套并存的编成表达——区间式（start_num~end_num）与列表式（nums 逗号串），
 * 语义重叠且后者无法建索引。新契约只承认「逐人记录」，区间/列表两种导入写法由 API 层
 * 展开后再落库。因此本表以 {@code (planId, vestNum)} 唯一。
 *
 * <p>{@code combatRole} / {@code weaponKind} 刻意不落库：契约明确它们由 {@code vestNum}
 * 的编号区间推导（≤1800 单兵、1801~4800 重武器/载具、65533~65535 危险物），
 * 存下来就会有与推导规则不一致的风险。
 *
 * @param vestNum 马甲编号，引用 {@code t_vest.vest_num}
 * @param hp      开演初始生命值
 * @param mode    轮询模式（下行 14B 轮询配置帧的 mode 参数）
 */
public record RosterEntry(
        long id,
        UUID planId,
        int vestNum,
        String name,
        Team team,
        int hp,
        int ammo1,
        int ammo2,
        PollMode mode,
        Instant createdAt,
        Instant updatedAt) {
}
