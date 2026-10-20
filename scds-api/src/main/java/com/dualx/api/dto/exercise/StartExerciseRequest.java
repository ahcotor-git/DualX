package com.dualx.api.dto.exercise;

import com.dualx.core.domain.BattleMode;
import jakarta.validation.constraints.NotBlank;

/**
 * 开始演习的请求体（契约 domain.yaml → {@code StartExerciseRequest}）。
 *
 * @param planId     想定 ID，必填
 * @param battleMode 演习模式，不传则沿用想定里的值
 */
public record StartExerciseRequest(@NotBlank String planId, BattleMode battleMode) {
}
