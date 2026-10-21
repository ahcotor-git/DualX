package com.dualx.api.dto.map;

import jakarta.validation.constraints.NotNull;

/**
 * UWB 基站在底图上的位置（契约 domain.yaml → {@code UwbAnchorPoint}）。
 *
 * <p>{@code xPx}/{@code yPx} 是**底图像素坐标**（原点左上），与冻结的 {@code uwb_meta}
 * 结构及解算链路同一口径。此处刻意不加 0~1 上限：像素上限取决于底图宽高，只有服务端在
 * 保存 {@code uwb_meta} 时才知道 {@code image_width}/{@code image_height}，
 * 放断言在这里会把「越界」误报成字段格式错误。</p>
 *
 * @param xPx 底图像素横坐标
 * @param yPx 底图像素纵坐标
 */
public record UwbAnchorPoint(
        @NotNull Double xPx,
        @NotNull Double yPx) {
}
