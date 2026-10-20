package com.dualx.api.dto.map;

import jakarta.validation.constraints.NotNull;

/**
 * UWB 基站集合（契约 domain.yaml → {@code UwbMeta.anchors}）。
 *
 * <p><b>基站编号与 41B 帧测距数组固定一一对应</b>：{@code bs(i+1)} ↔ {@code ranges[i]}，
 * 不可错位。bs1~bs4 为矩形四角，必须给全（bs1↔bs2 提供长度基准、bs1↔bs3 提供宽度基准）。</p>
 *
 * @param bs1 矩形角点 1（长度基准起点）
 * @param bs2 矩形角点 2（与 bs1 构成长度基准）
 * @param bs3 矩形角点 3（与 bs1 构成宽度基准）
 * @param bs4 矩形角点 4
 * @param bs5 可选基站
 * @param bs6 可选基站
 * @param bs7 可选基站
 * @param bs8 可选基站
 */
public record UwbAnchors(
        @NotNull UwbAnchorPoint bs1,
        @NotNull UwbAnchorPoint bs2,
        @NotNull UwbAnchorPoint bs3,
        @NotNull UwbAnchorPoint bs4,
        UwbAnchorPoint bs5,
        UwbAnchorPoint bs6,
        UwbAnchorPoint bs7,
        UwbAnchorPoint bs8) {
}
