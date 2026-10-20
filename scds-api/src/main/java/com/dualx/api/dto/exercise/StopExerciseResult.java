package com.dualx.api.dto.exercise;

import java.time.Instant;

/**
 * 结束演习的结果（契约 paths/exercise.yaml → {@code stopExercise} 的 200 响应体，契约中为内联未命名对象）。
 *
 * @param exerciseId 演习 ID
 * @param endedAt    结束时刻
 * @param replayId   复盘记录 ID，可用于立即跳转复盘；落库失败为 null
 */
public record StopExerciseResult(String exerciseId, Instant endedAt, String replayId) {
}
