package com.dualx.api.dto.common;

/**
 * 分页元信息（契约 common.yaml → {@code PageMeta}）。
 *
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 * @param total 满足条件的总行数
 */
public record PageMeta(int page, int size, long total) {
}
