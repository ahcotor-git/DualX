package com.dualx.api.dto.map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 楼层点位（契约 domain.yaml → {@code FloorPointsRequest.points[]}）。
 *
 * <p>点位号 {@code posNum} 与协议 A {@code ff03} 长版（15B）上报的 posNum 对应。
 * {@code xPct}/{@code yPct} 为底图归一化坐标（0~1，原点左上）：
 * 室内归一化坐标由服务端映射，前端不得做 y 翻转。</p>
 *
 * @param posNum 点位号
 * @param xPct   归一化横坐标（0~1）
 * @param yPct   归一化纵坐标（0~1）
 * @param label  备注标签，可空
 */
public record FloorPoint(
        @NotNull Integer posNum,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double xPct,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double yPct,
        String label) {
}
