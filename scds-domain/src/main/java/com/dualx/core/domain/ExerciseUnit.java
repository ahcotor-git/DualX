package com.dualx.core.domain;

import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 演习编成快照（{@code t_exercise_unit}）：开演瞬间从想定编成拷一份。
 *
 * <p>快照而非引用：想定事后必被改（下一场要换人换队伍），复盘必须看到「当时那场」的编成。
 *
 * <p>不下发 {@code combatRole} / {@code weaponKind}：二者由 {@code vestNum} 编号区间推导
 * （见 V3 说明），存下来只会与推导规则产生版本歧义。
 *
 * @param id          自增代理键，由库生成
 * @param finalStatus 停演时冻结的终局状态；开演时为 null（演中状态在态势域）
 * @param deadAt      阵亡时刻，复盘存活曲线的依据
 * @param kills       击杀数，停演时冻结
 * @param hitsDealt   命中数，停演时冻结
 */
public record ExerciseUnit(
        long id,
        UUID exerciseId,
        int vestNum,
        String name,
        Team team,
        VestStatus finalStatus,
        Instant deadAt,
        int kills,
        int hitsDealt,
        Instant createdAt,
        Instant updatedAt) {
}
