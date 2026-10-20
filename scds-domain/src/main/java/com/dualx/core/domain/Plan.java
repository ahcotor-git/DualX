package com.dualx.core.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 想定（{@code t_plan}，旧称「战斗基础配置」）。
 *
 * <p>旧表同时有 mapId / indoor_mapId / outdoor_mapId 三个地图字段，新契约收敛为
 * 单一 {@code mapId}（内外双图不再并存，室外底图方案待定）。
 *
 * <p>契约的 {@code rosterCount} 是派生值，不落库，由 API 层聚合。
 *
 * @param mapId 绑定的底图；可为空表示未指定
 */
public record Plan(
        UUID id,
        String name,
        UUID mapId,
        BattleMode battleMode,
        String remark,
        Instant createdAt,
        Instant updatedAt) {
}
