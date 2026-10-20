package com.dualx.api.dto.replay;

import com.dualx.core.domain.BattleMode;

import java.time.Instant;

/**
 * 复盘概览（契约 domain.yaml → {@code Replay}），{@code GET /api/v1/replays/{replayId}} 的响应体。
 *
 * <p>只含场次元信息，不含轨迹——轨迹走 timeline 分片拉取。</p>
 *
 * @param replayId        复盘 ID（与 {@code exerciseId} 同值，不建独立 replay 表）
 * @param planId          想定 ID，未关联时为 null
 * @param planName        想定名
 * @param mapId           底图 ID，未关联时为 null
 * @param battleMode      演习模式，复用核心领域枚举 {@code BattleMode}
 * @param startedAt       开演时刻
 * @param endedAt         停演时刻，进行中为 null
 * @param durationSeconds 演习时长（秒）
 * @param participantCount 参演人数
 * @param hitCount        命中数
 * @param deadCount       阵亡数
 */
public record Replay(String replayId, String planId, String planName, String mapId, BattleMode battleMode,
                     Instant startedAt, Instant endedAt, Long durationSeconds, Integer participantCount,
                     Integer hitCount, Integer deadCount) {
}
