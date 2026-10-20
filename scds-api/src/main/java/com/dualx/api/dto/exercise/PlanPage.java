package com.dualx.api.dto.exercise;

import com.dualx.api.dto.common.PageMeta;

import java.util.List;

/**
 * 想定分页结果（契约 domain.yaml → {@code PlanPage}）。
 *
 * @param items 当前页想定
 * @param meta  分页元信息
 */
public record PlanPage(List<Plan> items, PageMeta meta) {
}
