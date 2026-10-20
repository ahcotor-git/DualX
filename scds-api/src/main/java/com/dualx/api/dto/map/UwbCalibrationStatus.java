package com.dualx.api.dto.map;

import java.time.Instant;

/**
 * UWB 校准状态（契约 domain.yaml → {@code UwbCalibrationStatus}）。
 *
 * @param state         校准状态
 * @param progressPct   进度百分比（0~100），非 RUNNING 时为 null
 * @param sampledFrames 已采样帧数
 * @param appliedAt     结果生效时刻，未生效时为 null
 * @param message       状态说明，可空
 */
public record UwbCalibrationStatus(UwbCalibrationState state, Integer progressPct, Integer sampledFrames,
                                   Instant appliedAt, String message) {
}
