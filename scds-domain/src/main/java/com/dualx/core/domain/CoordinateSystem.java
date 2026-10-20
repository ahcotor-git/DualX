package com.dualx.core.domain;

/**
 * 坐标系（契约 {@code GeoPoint.coordinateSystem}）。同一坐标载体里室内外两套值并存，
 * 由本字段指明哪一套有效：{@code INDOOR} 看 {@code xPct}/{@code yPct}，
 * {@code OUTDOOR} 看 {@code lng}/{@code lat} 与 {@code wgs84Lng}/{@code wgs84Lat}。
 */
public enum CoordinateSystem {

    /** 室内：底图归一化坐标。 */
    INDOOR,
    /** 室外：BD-09 / WGS-84 经纬度。 */
    OUTDOOR
}
