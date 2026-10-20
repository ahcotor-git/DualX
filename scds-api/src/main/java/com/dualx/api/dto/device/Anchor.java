package com.dualx.api.dto.device;

/**
 * UWB 基站台账条目（契约 domain.yaml → {@code Anchor}）。
 *
 * @param anchorId 基线编号 1..8，直接对应 41B 帧的 ranges[anchorId-1]
 * @param name     基站名称
 * @param xPx      底图归一化横坐标，0~1
 * @param yPx      底图归一化纵坐标，0~1
 * @param enabled  是否启用；bs1~bs4 为矩形四角，必开
 */
public record Anchor(int anchorId, String name, Double xPx, Double yPx, boolean enabled) {
}
