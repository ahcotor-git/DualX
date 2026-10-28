package com.dualx.api.dto.map;

import com.dualx.core.domain.MapType;

import java.time.Instant;

/**
 * 底图详情（契约 domain.yaml → {@code MapDetail}）。
 *
 * <p>{@code type} 复用仓储层枚举 {@link MapType}（取值 INDOOR/OUTDOOR 与契约逐字一致）。</p>
 *
 * @param mapId       底图 ID
 * @param name        名称
 * @param type        底图类型
 * @param imageUrl    底图签名访问 URL，无图时为 null
 * @param imageWidth  图片宽度（像素），未上传时为 null
 * @param imageHeight 图片高度（像素），未上传时为 null
 * @param floorCount  楼层数
 * @param calibrated  UWB 标定参数（bs1~bs4 + 长宽基准）是否齐备
 * @param calibratedAt 最近一次写入标定参数的时刻；从未标定（或标定早于 V11 校准列）时为 null。
 *                     与 {@code calibrated} 同源但不等价——本字段非空必然已齐备，反之不成立
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record MapDetail(String mapId, String name, MapType type, String imageUrl,
                        Integer imageWidth, Integer imageHeight, int floorCount,
                        boolean calibrated, Instant calibratedAt, Instant createdAt, Instant updatedAt) {
}
