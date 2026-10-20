package com.dualx.core.domain;

import com.dualx.protocol.Team;

import java.time.Instant;

/**
 * 马甲设备台账（{@code t_vest}）。
 *
 * <p>主键是协议里的 uint16 编号 {@code vestNum}，而不是 UUID——全系统（编成、轨迹、
 * 命中、态势）都用它标识设备，再插一个代理主键只会多一次映射。
 *
 * @param team           契约 {@code Team} 枚举；未编入想定时为空
 * @param simNo          SIM 号，来自 {@code ff0a}（18B）上报
 * @param bindSecretHash 单兵 App 绑定密钥的 BCrypt 摘要；以 SIM 号作密钥时为空
 * @param bound          是否已被某个单兵终端 App 认领（与 {@code enabled} 是两件事）
 * @param enabled        {@code false}=报修/退役，演习中忽略该设备
 * @param lastSeenAt     最后一次收到该马甲的合法帧时刻（UTC）
 */
public record Vest(
        int vestNum,
        String name,
        Team team,
        String simNo,
        String model,
        String firmwareVersion,
        String bindSecretHash,
        boolean bound,
        boolean enabled,
        Instant lastSeenAt,
        String remark,
        Instant createdAt,
        Instant updatedAt) {
}
