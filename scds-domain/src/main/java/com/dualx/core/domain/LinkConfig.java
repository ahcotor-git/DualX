package com.dualx.core.domain;

import java.time.Instant;

/**
 * 链路配置（{@code t_link_config}）——只存配置，不存运行态。
 *
 * <p>契约的 {@code LinkStatus}（state / fdCount / framesTotal / droppedTotal /
 * crcErrorTotal / lastFrameAt）描述的是进程内状态，落库即失真，因此完全不落库，
 * 由运行中的链路对象实时提供。
 *
 * @param serialFlowControlRtsCts 硬件流控开关
 * @param uwbBindHost             仅 {@link LinkKind#UWB_TCP_SERVER}：监听地址
 * @param uwbBindPort             仅 {@link LinkKind#UWB_TCP_SERVER}：监听端口，硬约束 12001
 * @param enabled                 开机自动打开；与链路当前是否打开无关
 */
public record LinkConfig(
        LinkKind linkKind,
        String serialPortName,
        Integer serialBaudRate,
        boolean serialFlowControlRtsCts,
        String uwbBindHost,
        Integer uwbBindPort,
        boolean enabled,
        String remark,
        Instant createdAt,
        Instant updatedAt) {
}
