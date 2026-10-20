package com.dualx.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 统一错误响应体：RFC 7807 {@code application/problem+json}
 * 在标准字段上扩展 {@code code} / {@code traceId} / {@code errors}（契约 common.yaml → Problem）。
 *
 * <p>{@code code} 与 {@code traceId} 是必填，前端与排障都依赖它们，任何时候都不得为 null。</p>
 *
 * @param type     RFC 7807 类型标识，骨架期统一 {@code about:blank}
 * @param title    人类可读摘要，仅供展示
 * @param status   HTTP 状态码
 * @param code     稳定业务错误码，前端据此分支
 * @param detail   补充说明
 * @param instance 出错的请求路径
 * @param traceId  服务端日志追踪号
 * @param errors   字段级校验错误，仅 {@code code=VALIDATION_FAILED} 时出现
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String type, String title, int status, ApiErrorCode code,
                         String detail, String instance, String traceId, List<FieldError> errors) {

    public static final String TYPE_ABOUT_BLANK = "about:blank";

    /** 字段级校验错误明细。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldError(String field, String message) {
    }
}
