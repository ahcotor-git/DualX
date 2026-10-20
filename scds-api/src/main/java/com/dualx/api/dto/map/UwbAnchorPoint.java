package com.dualx.api.dto.map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * UWB 基站在底图上的位置（契约 domain.yaml → {@code UwbAnchorPoint}）。
 *
 * <p>{@code xPx}/{@code yPx} 为底图归一化坐标（0~1，原点左上）：
 * 室内归一化坐标由服务端映射，前端不得做 y 翻转。</p>
 *
 * @param xPx 归一化横坐标（0~1）
 * @param yPx 归一化纵坐标（0~1）
 */
public record UwbAnchorPoint(
        @NotNull @DecimalMin("0") @DecimalMax("1") Double xPx,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double yPx) {
}
