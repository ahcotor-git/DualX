package com.dualx.api.dto.device;

import java.time.Instant;

/**
 * 单个马甲的链路状态（契约 paths/device.yaml → {@code getVestLink} 响应体）。
 *
 * <p>在线判定：距最后一次心跳不超过 5000ms 为在线；此处的在线视角与态势域的 VestStatus 一致。</p>
 *
 * @param vestNum         马甲编号
 * @param online          是否在线
 * @param lastHeartbeatAt 最后一次心跳时刻
 * @param lastFrameType   最近一次收到的上行帧类型（ff03/ff04/ff05/ff07/ff0a）
 * @param framesTotal     累计收到的帧数
 * @param droppedTotal    累计丢弃的帧数
 */
public record VestLinkStatus(int vestNum, boolean online, Instant lastHeartbeatAt, String lastFrameType,
                             long framesTotal, long droppedTotal) {
}
