package com.dualx.api.dto.device;

import com.dualx.protocol.Team;

import java.time.Instant;

/**
 * 马甲台账条目（契约 domain.yaml → {@code VestDevice}）。
 *
 * <p>刻意不回显 {@code bindSecretHash}：摘要属于凭据，契约里没有这个字段，
 * 列表与详情都不该有第二个出口（评审 R22 的同类思想）。</p>
 *
 * @param vestNum         马甲编号，1~65532
 * @param name            使用人姓名
 * @param team            所属队伍
 * @param simNo           SIM 号，来自 ff0a（18B）上报
 * @param model           设备型号
 * @param firmwareVersion 固件版本
 * @param remark          台账备注，与 {@code VestDeviceUpsertRequest.remark} 同源
 * @param bound           是否已与某个单兵终端 App 绑定
 * @param enabled         台账启用状态；{@code false} 即注销/退役，演习中忽略该设备
 * @param lastSeenAt      最后一次出现时刻
 * @param createdAt       登记时间
 * @param updatedAt       最后修改时间
 */
public record VestDevice(int vestNum, String name, Team team, String simNo, String model, String firmwareVersion,
                         String remark, boolean bound, boolean enabled, Instant lastSeenAt,
                         Instant createdAt, Instant updatedAt) {
}
