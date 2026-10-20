package com.dualx.api.dto.situation;

import com.dualx.api.dto.common.PageMeta;

import java.util.List;

/**
 * 演习日志分页（契约 situation.yaml → {@code ExerciseLogPage}）。
 *
 * @param items 当前页日志条目
 * @param meta  分页元信息
 */
public record ExerciseLogPage(List<ExerciseLogEntry> items, PageMeta meta) {
}
