package com.dualx.api.dto.situation;

import com.dualx.protocol.Team;

/**
 * 队伍汇总（契约 situation.yaml → {@code TeamSummary}）。
 *
 * @param team      队伍
 * @param total     总人数
 * @param live      存活数
 * @param dead      阵亡数
 * @param offline   离线数
 * @param hitsDealt 该队伍造成的命中数
 */
public record TeamSummary(
        Team team,
        Integer total,
        Integer live,
        Integer dead,
        Integer offline,
        Integer hitsDealt) {
}
