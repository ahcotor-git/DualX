package com.dualx.core.push;

import com.dualx.protocol.Team;

/**
 * 队伍汇总（契约 situation.yaml → {@code TeamSummary}）。
 *
 * <p>这是**线载荷**：HTTP `GET /situation/teams/summary` 与 WS 帧的 {@code teamSummary} 共用同一个
 * Java 类型（设计 02 §4.3）。原住 {@code com.dualx.api.dto.situation}，为让 core 能直接构造它而下沉到
 * 中立的 {@code com.dualx.core.push}；复盘的 {@code ReplayStatistics.teams} 也复用本类型。</p>
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
