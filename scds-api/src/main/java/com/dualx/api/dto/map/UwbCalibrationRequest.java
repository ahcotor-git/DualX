package com.dualx.api.dto.map;

/**
 * 触发 UWB 校准的可选参数（契约 domain.yaml → {@code UwbCalibrationRequest}）。
 *
 * <p>全部字段可空：缺省时服务端按 scds-solver 的 {@code calibSampleFrames}（默认 200）采样。</p>
 *
 * @param sampleFrames 采样帧数，默认 200
 * @param bs1ToBs2Cm   现场卷尺实测长度（厘米），用于反推标定系数
 * @param bs1ToBs3Cm   现场卷尺实测宽度（厘米）
 */
public record UwbCalibrationRequest(Integer sampleFrames, Double bs1ToBs2Cm, Double bs1ToBs3Cm) {
}
