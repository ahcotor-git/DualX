package com.dualx.core.domain;

import com.dualx.protocol.VestStatus;

import java.util.UUID;

/**
 * 轨迹采样（{@code t_track_sample}）。全库写入量最大的表，由应用层「降采样 + 批量异步
 * flush」产出，不逐帧落库。
 *
 * <p>不存绝对时间：绝对时刻 = {@code t_exercise.started_at + tMs}。冗余一列 DATETIME(3)
 * 在数十万行上是白多出的体积，而复盘界面本就用相对时间轴。
 *
 * @param id       自增代理键；分片游标取它，客户端按 {@code tMs} 自行排序
 * @param tMs      相对开演的毫秒偏移，时间轴唯一依据；写满约 24.85 天（INT）
 * @param status   停演/阵亡期间仍按原值持续落点，以便复盘还原静止画面
 * @param motion   契约 {@code MotionState}；未判定时为 {@code null}
 * @param position 坐标；未定位时为 {@code null}
 * @param sampleReason 为何记了这一点，供抽稀与排障辨识语义点
 */
public record TrackSample(
        long id,
        UUID exerciseId,
        int vestNum,
        int tMs,
        VestStatus status,
        Integer hp,
        Integer ammo1,
        Integer ammo2,
        MotionState motion,
        PositionSource positionSource,
        Integer signalQuality,
        GeoPoint position,
        SampleReason sampleReason) {
}
