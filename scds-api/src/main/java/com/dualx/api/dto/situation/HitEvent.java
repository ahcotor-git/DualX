package com.dualx.api.dto.situation;

import com.dualx.core.domain.GeoPoint;
import com.dualx.protocol.HitPart;
import com.dualx.protocol.WeaponKind;

import java.time.Instant;

/**
 * 命中事件（契约 situation.yaml → {@code HitEvent}），源自协议 {@code ff05} 上报。
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
