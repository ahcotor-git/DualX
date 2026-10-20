package com.dualx.api.dto.map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * UWB 解算坐标系参数（契约 domain.yaml → {@code UwbMeta}，旧系统 uwb_meta.json）。
 *
 * <p>基站位置 {@code anchors} 为底图归一化坐标（0~1，原点左上）：
 * 室内归一化坐标由服务端映射，前端不得做 y 翻转。
 * 本类型同时作为 {@code putUwbMeta} 请求体与 {@code getUwbMeta}/{@code putUwbMeta} 响应体。</p>
 *
 * @param anchors      基站集合，bs1~bs4 必填
 * @param realLengthCm bs1↔bs2 现场实测长度（厘米）
 * @param realWidthCm  bs1↔bs3 现场实测宽度（厘米）
 */
public record UwbMeta(
        @NotNull @Valid UwbAnchors anchors,
        @NotNull Double realLengthCm,
        @NotNull Double realWidthCm) {
}
