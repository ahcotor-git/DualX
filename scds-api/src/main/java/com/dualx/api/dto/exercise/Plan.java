package com.dualx.api.dto.exercise;

import com.dualx.core.domain.BattleMode;

import java.time.Instant;

/**
 * 想定（契约 domain.yaml → {@code Plan}），列表与详情接口的响应体。
 *
 * @param planId      想定 ID
 * @param name        想定名称
 * @param mapId       绑定的底图，可为 null
 * @param battleMode  演习模式（训练 / 正式）
 * @param remark      备注，可为 null
 * @param rosterCount 参演人数（派生值，不落库）
 * @param createdAt   创建时刻
 * @param updatedAt   最近更新时刻，可为 null
 */
public record Plan(String planId, String name, String mapId, BattleMode battleMode, String remark,
                   Integer rosterCount, Instant createdAt, Instant updatedAt) {
}
