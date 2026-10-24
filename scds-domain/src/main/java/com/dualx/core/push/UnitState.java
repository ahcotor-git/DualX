package com.dualx.core.push;

import com.dualx.core.domain.GeoPoint;
import com.dualx.core.domain.MotionState;
import com.dualx.core.domain.PositionSource;
import com.dualx.protocol.Team;
import com.dualx.protocol.VestStatus;
import com.dualx.protocol.WeaponKind;

import java.time.Instant;

/**
 * 单个参演单元（马甲 / 武器 / 载具）的实时状态（契约 situation.yaml → {@code UnitState}）。
 *
 * <p>这是**线载荷**：HTTP Poll 响应与 WS 帧体共用同一个 Java 类型（设计 02 §4.3）。
 * 原住 {@code com.dualx.api.dto.situation}，为让 core 能直接持有与构造它而下沉到中立的
 * {@code com.dualx.core.push}。注意与 {@code com.dualx.core.domain.HitEvent}（库实体）不同：
 * 本类字段形状由契约决定，不得为「统一」而增删。</p>
 *
 * <p><b>阵亡冻结硬约束</b>：单元阵亡后必须冻结在最后有效坐标，后续上报的坐标（含 UWB 解算）
 * 一律丢弃；此处的 {@code position} / {@code motion} 不再变化，{@code motion = FROZEN}。</p>
 *
 * @param vestNum         马甲编号（协议 uint16）
 * @param name            名称，未命名为 null
 * @param team            所属队伍
 * @param combatRole      战斗角色，由编号区间推导
 * @param weaponKind      重武器/载具种类；单兵为 null
 * @param status          马甲状态，在线判定由心跳驱动（距最后心跳超 5000ms 即 OFFLINE）
 * @param hp              生命值 0~255
 * @param ammo1           主武器弹药
 * @param ammo2           副武器弹药
 * @param position        坐标；离线/未定位为 null，阵亡后必须冻结在最后有效位置
 * @param motion          运动状态
 * @param signalQuality   UWB 可用基站数（0~8），小于 2 时坐标不可信
 * @param positionSource  坐标来源
 * @param lastHeartbeatAt 最后一次心跳时刻
 * @param deadAt          阵亡时刻，用于复盘回放
 * @param revision        该单元最后一次变化的全局 revision
 */
public record UnitState(
        int vestNum,
        String name,
        Team team,
        CombatRole combatRole,
        WeaponKind weaponKind,
        VestStatus status,
        Integer hp,
        Integer ammo1,
        Integer ammo2,
        GeoPoint position,
        MotionState motion,
        Integer signalQuality,
        PositionSource positionSource,
        Instant lastHeartbeatAt,
        Instant deadAt,
        long revision) {
}
