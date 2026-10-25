package com.dualx.api.dto.exercise;

import com.dualx.core.domain.ExerciseEndReason;

import java.time.Instant;

/**
 * 结束演习的结果（契约 paths/exercise.yaml → {@code stopExercise} 的 200 响应体，契约中为内联未命名对象）。
 *
 * @param exerciseId 演习 ID
 * @param endedAt    结束时刻
 * @param replayId   复盘记录 ID，可用于立即跳转复盘；复盘未落库（含落库失败）为 null
 * @param endReason  结束原因；走本端点恒为 {@code NORMAL}，{@code CRASH_RECOVERED} 只出现在自愈补写的场次
 */
public record StopExerciseResult(String exerciseId, Instant endedAt, String replayId,
                                 ExerciseEndReason endReason) {
}
