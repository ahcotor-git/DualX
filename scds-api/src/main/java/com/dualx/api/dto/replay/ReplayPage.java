package com.dualx.api.dto.replay;

import com.dualx.api.dto.common.PageMeta;

import java.util.List;

/**
 * 复盘列表分页（契约 domain.yaml → {@code ReplayPage}），{@code GET /api/v1/replays} 的响应体。
 *
 * @param items 当前页复盘概览
 * @param meta  分页元信息
 */
public record ReplayPage(List<Replay> items, PageMeta meta) {
}
