package com.dualx.api.dto.exercise;

import com.dualx.core.domain.BattleMode;

import java.time.Instant;

/**
 * 当前演习状态（契约 domain.yaml → {@code Exercise}）；无演习进行中时 {@code running=false}。
 *
 * @param exerciseId     演习 ID
 * @param planId         想定 ID
 * @param planName       想定名快照
 * @param mapId          底图快照，可为 null
 * @param battleMode     演习模式
 * @param running        是否进行中
 * @param startedAt      开始时刻
 * @param endedAt        结束时刻，进行中为 null
 * @param elapsedSeconds 已进行秒数
 * @param rosterCount    参演人数
 * @param onlineCount    心跳在 5000ms 窗口内的马甲数
 */
public record Exercise(String exerciseId, String planId, String planName, String mapId, BattleMode battleMode,
                       boolean running, Instant startedAt, Instant endedAt, Long elapsedSeconds,
                       Integer rosterCount, Integer onlineCount) {
}
