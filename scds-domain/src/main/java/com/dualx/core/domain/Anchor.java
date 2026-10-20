package com.dualx.core.domain;

import java.time.Instant;

/**
 * UWB 基站台账（{@code t_anchor}，契约 {@code Anchor}）。
 *
 * <p>坐标存在双源：本表 {@code xPx}/{@code yPx} 与 {@code t_map.uwb_meta} 里的 {@code bsN}。
 * 已定优先级：**解算只读 {@code t_map.uwb_meta}**；本表是设备台账（名称、启用开关），
 * 其坐标用于「未绑定底图时的展示与初值」，由服务层负责把两处刷成一致。
 *
 * @param anchorId 1~8，与 41B 帧 {@code ranges[i]} 的 {@code i+1} 严格一一对应
 * @param xPx      底图像素横坐标（归一化 0~1）
 * @param yPx      底图像素纵坐标（归一化 0~1）
 * @param enabled  {@code bs5}~{@code bs8} 可关；{@code bs1}~{@code bs4} 为矩形四角，必开
 */
public record Anchor(
        int anchorId,
        String name,
        Double xPx,
        Double yPx,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
