package com.dualx.api.dto.map;

import java.util.List;

/**
 * 楼层点位查询结果（契约 map.yaml → {@code listFloorPoints} 200 响应体）。
 *
 * <p>{@code xPct}/{@code yPct} 为底图归一化坐标（0~1，原点左上）：
 * 室内归一化坐标由服务端映射，前端不得做 y 翻转。</p>
 *
 * @param points 点位列表
 */
public record FloorPointsResponse(List<FloorPoint> points) {
}
