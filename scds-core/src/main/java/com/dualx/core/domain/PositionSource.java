package com.dualx.core.domain;

/**
 * 坐标来源（契约 {@code UnitState.positionSource}）。
 *
 * <p>用于判断坐标的可信度：{@link #MANUAL} 是人工拖拽、{@link #UWB} 需配合
 * {@code signalQuality}（可用基站数 &lt; 2 时坐标不可信）。
 */
public enum PositionSource {

    /** UWB 解算。 */
    UWB,
    /** 室外 GPS / 北斗。 */
    GPS,
    /** 人工指定。 */
    MANUAL,
    /** 无坐标。 */
    NONE
}
