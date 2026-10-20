package com.dualx.core.domain;

/**
 * 链路种类（契约 {@code LinkKind}，见 docs/api/schemas/common.yaml）。
 * {@code t_link_config} 一行一种，全库最多三行。
 */
public enum LinkKind {

    /** 马甲串口（协议 A，文本 HEX/ASCII 帧）。 */
    SERIAL_VEST,
    /** UWB 走 TCP，服务端监听（**端口硬约束 12001**）。 */
    UWB_TCP_SERVER,
    /** UWB 走串口（与 {@link #UWB_TCP_SERVER} 二选一）。 */
    UWB_SERIAL
}
