package com.dualx.api.dto.map;

/**
 * UWB 校准状态机（契约 domain.yaml → {@code UwbCalibrationStatus.state}）。
 */
public enum UwbCalibrationState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED
}
