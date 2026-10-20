package com.dualx.api.dto.replay;

import com.dualx.protocol.VestStatus;

/**
 * 轨迹采样点（契约 domain.yaml → {@code TrackSample}）。
 *
 * <p>坐标按契约平铺为 xPct/yPct/lng/lat，不复用领域 {@code com.dualx.core.domain.TrackSample}
 * （后者带 exerciseId、motion、position 等非契约字段）。</p>
 *
 * @param t       相对开演的毫秒偏移，时间轴唯一依据
 * @param vestNum 马甲编号
 * @param xPct    室内归一化横坐标 0~1
 * @param yPct    室内归一化纵坐标 0~1
 * @param lng     BD-09 经度
 * @param lat     BD-09 纬度
 * @param hp      当前血量，未知为 null
 * @param status  马甲状态
 */
public record TrackSample(long t, int vestNum, Double xPct, Double yPct, Double lng, Double lat,
                          Integer hp, VestStatus status) {
}
