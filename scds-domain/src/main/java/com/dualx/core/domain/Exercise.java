package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 演习场次（{@code t_exercise}）。一行两用：进行中时是「当前演习」，
 * 写入 {@code endedAt} 之后即成复盘记录——因此 {@code replayId} 就是这里的 {@code id}，
 * 既不另建 {@code t_replay}，也没有「转存」动作（见 V4 文件头）。
 *
 * @param planName    想定名快照。想定可被改名或删除，而复盘列表要按此模糊搜索，
 *                    所以快照而非引用
 * @param mapId       底图快照，室外纯 GPS 场合为 null
 * @param endedAt     null 即进行中；非 null 即已结束（可回放）
 * @param rosterCount 参演人数。开演时即为已知快照，故当次写入
 * @param hitCount    命中总数，停演时冻结
 * @param deadCount   阵亡总数，停演时冻结
 */
public record Exercise(
        UUID id,
        UUID planId,
        String planName,
        UUID mapId,
        BattleMode battleMode,
        Instant startedAt,
        Instant endedAt,
        int rosterCount,
        int hitCount,
        int deadCount,
        Instant createdAt,
        Instant updatedAt) {

    /** 是否进行中。库层的「至多一场」由生成列 running_flag 上的唯一索引保证。 */
    public boolean running() {
        return endedAt == null;
    }
}
