package com.dualx.api.error;

import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * 全局异常出口：把异常统一折叠成 {@code application/problem+json}。
 *
 * <p>不做「每种异常一个响应体形状」——契约只有一种错误形状（{@link ApiProblem}），
 * 前端才能用一套解析逻辑处理所有失败。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 骨架期占位接口：501，让「尚未实现」与「4xx 用错」在响应上可区分。 */
    @ExceptionHandler(NotImplementedException.class)
    public ResponseEntity<ApiProblem> handleNotImplemented(NotImplementedException ex, HttpServletRequest request) {
        log.warn("调用骨架期占位接口：{} {}（operationId={}）", request.getMethod(), request.getRequestURI(),
                ex.operationId());
        return problem(HttpStatus.NOT_IMPLEMENTED, ApiErrorCode.NOT_IMPLEMENTED, "接口尚未实现", request, null);
    }

    /** 请求体字段校验失败（{@code @Valid} 触发），逐字段回填 errors。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleBodyValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiProblem.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数校验未通过", request, errors);
    }

    /**
     * 方法级参数校验失败：类上 {@code @Validated} 配合参数注解（如 {@code @Min}）触发。
     *
     * <p>必须与 {@link MethodArgumentNotValidException} 分开登记——后者只覆盖请求体，
     * 路径变量与查询参数的约束违例走这一条，否则会被兜底分支吞成 500。</p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiProblem> handleParameterValidation(ConstraintViolationException ex,
                                                               HttpServletRequest request) {
        List<ApiProblem.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiProblem.FieldError(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数校验未通过", request, errors);
    }

    /** 查询参数缺失 / 类型不符 / 请求体不是合法 JSON —— 都算字段级校验失败。 */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiProblem> handleRequestBinding(Exception ex, HttpServletRequest request) {
        log.debug("请求绑定失败：{} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数校验未通过", request, null);
    }

    /**
     * 业务失败：core 抛 {@link BusinessException}，此处把 {@link ApiErrorCode} 折成 HTTP 状态。
     *
     * <p>{@code Retry-After} 只在限流时出现，且必须是**整秒**——契约与 RFC 7231 都只要秒粒度，
     * 写小数会让客户端解析失败。</p>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiProblem> handleBusiness(BusinessException ex, HttpServletRequest request) {
        HttpStatus status = statusOf(ex.code());
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("业务异常使用了尚未登记 HTTP 状态的错误码：{}，请先在本类补映射", ex.code());
        } else {
            log.debug("业务失败：{} {} → {} {}", request.getMethod(), request.getRequestURI(),
                    status.value(), ex.code());
        }
        ApiProblem body = problemBody(status, ex.code(), ex.getMessage(), request, null);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (ex.retryAfter() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfter().toSeconds()));
        }
        return builder.body(body);
    }

    /** 兜底：未预期异常一律 500，且必须带 traceId 才能排障。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常：{} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "服务端内部错误", request, null);
    }

    /**
     * 错误码 → HTTP 状态。
     *
     * <p>刻意不做「按前缀猜」：{@code EXERCISE_*} 里既有 404（计划不存在）又有 409（已在运行），
     * 猜错的代价是前端按状态分支时静默走错路。未登记的码落到 500 并在上面那条分支打 ERROR——
     * 响亮地失败，比返回一个看着合理的 400 更容易在联调期被抓到。</p>
     */
    private static HttpStatus statusOf(ApiErrorCode code) {
        return switch (code) {
            case AUTH_BAD_CREDENTIALS, AUTH_TOKEN_EXPIRED, AUTH_TOKEN_INVALID, AUTH_DEVICE_NOT_BOUND ->
                    HttpStatus.UNAUTHORIZED;
            case AUTH_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private ResponseEntity<ApiProblem> problem(HttpStatus status, ApiErrorCode code, String title,
                                               HttpServletRequest request, List<ApiProblem.FieldError> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemBody(status, code, title, request, errors));
    }

    private ApiProblem problemBody(HttpStatus status, ApiErrorCode code, String title,
                                   HttpServletRequest request, List<ApiProblem.FieldError> errors) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return new ApiProblem(ApiProblem.TYPE_ABOUT_BLANK, title, status.value(), code,
                null, request.getRequestURI(), traceId == null ? null : traceId.toString(), errors);
    }
}
