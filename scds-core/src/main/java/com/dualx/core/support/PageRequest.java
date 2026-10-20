package com.dualx.core.support;

/**
 * 分页请求。页码从 1 开始，取值口径与契约 PageParam / SizeParam 一致。
 *
 * <p>在这里做边界校验而不是靠调用方自觉：size 上限 200 是契约里写死的，
 * 放行更大的值会让一次列表查询把整库拉进内存。
 */
public record PageRequest(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1, got " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be in 1.." + MAX_SIZE + ", got " + size);
        }
    }

    /** 便于直接吃 HTTP 查询参数：为 null 时回落到契约默认值。 */
    public static PageRequest of(Integer page, Integer size) {
        return new PageRequest(page == null ? 1 : page, size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
