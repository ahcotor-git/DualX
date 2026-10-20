package com.dualx.core.domain;

/**
 * 链路开关状态（契约 common.yaml → {@code LinkState}）。
 *
 * <p>{@code OPENING} 是必要状态：串口打开与 UWB 监听启动都可能耗时数百毫秒，
 * 期间既不能报 {@code CLOSED}（已下发）也不能报 {@code OPEN}（尚未就绪）。</p>
 */
public enum LinkState {
    CLOSED,
    OPENING,
    OPEN,
    ERROR
}
