package com.dualx.api.dto.map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 楼层点位表（契约 domain.yaml → {@code FloorPointsRequest}）。
 *
 * <p>{@code xPct}/{@code yPct} 为底图归一化坐标（0~1，原点左上）：
 * 室内归一化坐标由服务端映射，前端不得做 y 翻转。</p>
 *
 * @param points 点位列表（整体替换语义）
 */
public record FloorPointsRequest(@NotNull @Valid List<FloorPoint> points) {
}
