package com.dualx.core.domain;

import com.dualx.protocol.HitPart;
import com.dualx.protocol.WeaponKind;

import java.time.Instant;
import java.util.UUID;

/**
 * 命中事件（{@code t_hit_event}，契约 {@code HitEvent}），源自协议 {@code ff05} 上报。
 *
 * <p><b>本类是库实体，与线载荷 {@link com.dualx.core.push.HitEvent} 有意分开，不要顺手统一</b>：
 * 本类多带 {@code exerciseId} 与 {@code tMs} 两个库列（后者与轨迹采样同原点、可混排）；
 * 线载荷的字段形状由契约决定，契约不含那两列。同理本类用基本类型，线载荷用可空包装类型。</p>
 *
 * <p>坐标在此冗余存一份（与 {@code t_track_sample} 同名同义）：命中是<b>离散事件</b>，
 * 复盘要定位到「打中那一刻的位置」，不能让客户端去轨迹里做最近邻插值反推——
 * 双采样点跨越位移时会算错。</p>
 *
 * @param seq               全局单调递增序号，对外即 {@code HitEvent.seq}；由库生成，写入时忽略
 * @param tMs               相对开演的毫秒偏移，与 {@code t_track_sample.t_ms} 同原点，可混排
 * @param hitVestNum        被击中者（ff05 字段一）
 * @param shooterNum        开枪者编号（ff05 字段二）
 * @param shooterIsSoldier  编号是否 ≤1800（单兵）
 * @param shooterWeaponKind 武器/载具种类；单兵命中为 {@code null}
 * @param lethal            服务端判定的致死结果，前端不再自算
 * @param position          被击中者当时坐标
 */
public record HitEvent(
        long seq,
        UUID exerciseId,
        Instant at,
        int tMs,
        int hitVestNum,
        int shooterNum,
        boolean shooterIsSoldier,
        WeaponKind shooterWeaponKind,
        HitPart hitPart,
        boolean lethal,
        GeoPoint position) {
}
