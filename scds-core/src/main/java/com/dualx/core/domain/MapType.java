package com.dualx.core.domain;

/**
 * 底图类型（契约 {@code MapType}，见 docs/api/schemas/domain.yaml）。
 *
 * <p>旧库用 int 0/1 表示：0=室外、1=室内；旧的 type 2（奥维）与 4（离线）
 * 随业务范围收缩一并废弃。
 */
public enum MapType {

    /** 室内底图，配 UWB 标定。 */
    INDOOR,
    /** 室外，用 GPS 经纬度，无底图亦可。 */
    OUTDOOR
}
