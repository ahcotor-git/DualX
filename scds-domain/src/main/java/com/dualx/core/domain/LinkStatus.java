package com.dualx.core.domain;

import java.time.Instant;

/**
 * 链路状态（契约 common.yaml → {@code LinkStatus}）。
 *
 * <p>HTTP {@code GET /api/v1/devices/links} 与 WebSocket {@code /ws/v1/devices/links}
 * 返回**同一个类型**，这是双通道「同构载荷」约定的落地方式。</p>
 *
 * <p>描述的是进程内运行态，落库即失真，因此完全不落库，由运行中的链路对象实时提供。
 * 放在领域内核而非 api 层，是因为它与 {@link LinkConfig} 同属「硬件端口」契约，
 * 需要被 api 与 gateway 两侧共同引用。</p>
 *
 * @param kind          链路种类，复用 {@link LinkKind}
 * @param state         开关状态
 * @param target        串口名（如 COM7）或监听地址（如 0.0.0.0:12001）
 * @param baudRate      串口波特率，非串口为 null
 * @param fdCount       UWB TCP Server 当前接入连接数
 * @param lastFrameAt   最近一次收到合法帧的时刻
 * @param framesTotal   本次开启以来收到的合法帧总数
 * @param droppedTotal  本次开启以来丢弃的坏帧总数
 * @param crcErrorTotal 校验失败的帧数
 * @param errorMessage  异常说明，仅 {@code state=ERROR} 时有效
 */
public record LinkStatus(LinkKind kind, LinkState state, String target, Integer baudRate, Integer fdCount,
                         Instant lastFrameAt, long framesTotal, long droppedTotal, long crcErrorTotal,
                         String errorMessage) {
}
