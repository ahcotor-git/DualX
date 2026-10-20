package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 底图（{@code t_map}）。命名避开 {@code java.util.Map}，与「底图」语义对齐。
 *
 * <p>一张图一份图片（旧库是一层一份图片，新契约收敛为 map 级单图 + {@code floorNum} 区分层）。
 *
 * @param imagePath 底图图片相对路径（旧 {@code t_map.path}）；未上传时为空
 * @param uwbMeta   UWB 基站标定 JSON，结构逐字节冻结（契约 {@code UwbMeta}）：
 *                  {@code {"bs1":{"xPx":..,"yPx":..},...,"realLengthCm":int,"realWidthCm":int}}。
 *                  解算链路只读本列；现场实测的长宽基准也沉淀在这里。
 *                  刻意保存原始 JSON 文本：结构由契约冻结，解析与校验属 API 层职责。
 */
public record BaseMap(
        UUID id,
        String name,
        MapType mapType,
        String imagePath,
        Integer imageWidth,
        Integer imageHeight,
        String remark,
        String uwbMeta,
        Instant createdAt,
        Instant updatedAt) {
}
