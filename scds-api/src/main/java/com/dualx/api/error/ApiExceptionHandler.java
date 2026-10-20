package com.dualx.api.error;

import com.dualx.api.support.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 兜底：未预期异常一律 500，且必须带 traceId 才能排障。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常：{} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "服务端内部错误", request, null);
    }

    private ResponseEntity<ApiProblem> problem(HttpStatus status, ApiErrorCode code, String title,
                                               HttpServletRequest request, List<ApiProblem.FieldError> errors) {
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiProblem body = new ApiProblem(ApiProblem.TYPE_ABOUT_BLANK, title, status.value(), code,
                null, request.getRequestURI(), traceId == null ? null : traceId.toString(), errors);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
