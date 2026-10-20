package com.dualx.core.support;

import java.util.List;

/**
 * 分页结果。字段与契约 PageMeta（page / size / total）对齐。
 *
 * @param items 当前页数据
 * @param total 满足条件的总行数（不受分页影响）
 * @param page  当前页码，从 1 开始
 * @param size  每页条数
 */
public record Page<T>(List<T> items, long total, int page, int size) {

    public static <T> Page<T> of(List<T> items, long total, PageRequest request) {
        return new Page<>(items, total, request.page(), request.size());
    }

    public long totalPages() {
        return size <= 0 ? 0 : (total + size - 1) / size;
    }
}
