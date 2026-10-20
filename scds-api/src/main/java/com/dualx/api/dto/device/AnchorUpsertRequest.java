package com.dualx.api.dto.device;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 配置 UWB 基站请求体（契约 domain.yaml → {@code AnchorUpsertRequest}）。
 *
 * @param name    基站名称
 * @param xPx     底图归一化横坐标，0~1
 * @param yPx     底图归一化纵坐标，0~1
 * @param enabled 是否启用
 */
public record AnchorUpsertRequest(String name,
                                  @Min(0) @Max(1) double xPx,
                                  @Min(0) @Max(1) double yPx,
                                  boolean enabled) {
}
