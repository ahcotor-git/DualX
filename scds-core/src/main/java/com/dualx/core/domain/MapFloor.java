package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 楼层点位表（{@code t_map_floor}）。
 *
 * <p>契约没有独立的「楼层实体」，楼层只由 {@code floorNum} 标识，
 * 且一张底图下 {@code (mapId, floorNum)} 唯一。
 *
 * @param floorNum 楼层号，来自 {@code ff03} 长版上报的 floorNum
 * @param points   点位表 JSON，结构冻结为 {@code [{posNum,xPct,yPct,label}]}；
 *                 刻意保存原始 JSON 文本，解析与校验属 API 层职责
 */
public record MapFloor(
        long id,
        UUID mapId,
        int floorNum,
        String points,
        Instant createdAt,
        Instant updatedAt) {
}
