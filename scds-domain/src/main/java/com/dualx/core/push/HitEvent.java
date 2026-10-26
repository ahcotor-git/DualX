package com.dualx.core.push;

import com.dualx.core.domain.GeoPoint;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.WeaponKind;

import java.time.Instant;

/**
 * 命中事件（契约 situation.yaml → {@code HitEvent}），源自协议 {@code ff05} 上报。
 *
 * <p>这是**线载荷**：HTTP Poll 的 {@code hits} 与 WS 帧的 {@code hits} 共用同一个 Java 类型
 * （设计 02 §4.3）。原住 {@code com.dualx.api.dto.situation}，为让 core 能直接构造它而下沉到中立的
 * {@code com.dualx.core.push}。</p>
 *
 * <p><b>与 {@link com.dualx.core.domain.HitEvent} 是两个有意分开的类型，不要顺手统一</b>：
 * 后者是**库实体**（{@code t_hit_event}），多带 {@code exerciseId} 与 {@code tMs} 两个库列；
 * 本类的字段形状由契约决定（契约不含那两列，演习作用域由请求上下文给出）。
 * 同理本类用包装类型 + 可空，库实体用基本类型。</p>
 *
 * @param seq               全局单调递增的命中序号，用于去重与增量拉取
 * @param at                命中时刻
 * @param hitVestNum        被击中者（ff05 字段一）
 * @param shooterNum        开枪者编号（ff05 字段二），小于等于 1800 为单兵
 * @param shooterIsSoldier  开枪者是否为单兵
 * @param shooterWeaponKind 开枪者武器/载具种类；单兵为 null
 * @param hitPart           击中部位
 * @param lethal            本次命中是否致死（服务端判定结果，前端不再自行计算）
 * @param position          被击中者当时的坐标
 */
public record HitEvent(
        long seq,
        Instant at,
        Integer hitVestNum,
        Integer shooterNum,
        Boolean shooterIsSoldier,
        WeaponKind shooterWeaponKind,
        HitPart hitPart,
        Boolean lethal,
        GeoPoint position) {
}
