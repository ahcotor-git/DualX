package com.dualx.api.dto.auth;

import com.dualx.api.dto.situation.UnitState;
import com.dualx.protocol.Team;

/**
 * 单兵终端自身信息与实时状态（契约 domain.yaml → {@code DeviceSelf}）。
 *
 * @param vestNum 马甲编号
 * @param name    马甲名称，可为 null
 * @param team    所属队伍
 * @param state   自身实时状态，与态势域同一模型（{@link UnitState}）
 */
public record DeviceSelf(int vestNum, String name, Team team, UnitState state) {
}
