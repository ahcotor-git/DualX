package com.dualx.api.error;

import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 全局异常出口：把异常统一折叠成 {@code application/problem+json}。
 *
 * <p>不做「每种异常一个响应体形状」——契约只有一种错误形状（{@link ApiProblem}），
 * 前端才能用一套解析逻辑处理所有失败。</p>
 */
@RestControllerAdvice
// Boot's default ProblemDetails advice has lowest precedence; keep it enabled as a fallback.
@Order(-1)
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
        return validation(request, bindingErrors(ex.getBindingResult()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiProblem> handleModelBinding(BindException ex, HttpServletRequest request) {
        return validation(request, bindingErrors(ex.getBindingResult()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiProblem> handleMvcMethodValidation(HandlerMethodValidationException ex,
                                                               HttpServletRequest request) {
        // A bad controller return value is a server defect, not an invalid client request.
        if (ex.isForReturnValue()) {
            log.error("MVC 返回值校验失败：{} {}", request.getMethod(), request.getRequestURI(), ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "服务端内部错误", request, null);
        }
        List<ApiProblem.FieldError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = parameterName(result.getMethodParameter());
            result.getResolvableErrors().forEach(error ->
                    errors.add(new ApiProblem.FieldError(field, safeMessage(error.getDefaultMessage()))));
        });
        ex.getCrossParameterValidationResults().forEach(error ->
                errors.add(new ApiProblem.FieldError("$", safeMessage(error.getDefaultMessage()))));
        return validation(request, errors);
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
        boolean invalidReturn = ex.getConstraintViolations().stream().anyMatch(violation ->
                StreamSupport.stream(violation.getPropertyPath().spliterator(), false)
                        .anyMatch(node -> node.getKind() == ElementKind.RETURN_VALUE));
        if (invalidReturn) {
            log.error("方法返回值校验失败：{} {}", request.getMethod(), request.getRequestURI(), ex);
            return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "服务端内部错误", request, null);
        }
        List<ApiProblem.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiProblem.FieldError(String.valueOf(v.getPropertyPath()), safeMessage(v.getMessage())))
                .toList();
        return validation(request, errors);
    }

    /** 查询参数缺失 / 类型不符 / 请求体不是合法 JSON —— 都算字段级校验失败。 */
    @ExceptionHandler({ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiProblem> handleRequestBinding(Exception ex, HttpServletRequest request) {
        log.debug("请求绑定失败：{} {}", request.getMethod(), request.getRequestURI(), ex);
        String field = "$";
        if (ex instanceof MissingServletRequestParameterException missing) {
            field = missing.getParameterName();
        } else if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            field = mismatch.getName();
        } else if (ex instanceof MissingServletRequestPartException missing) {
            field = missing.getRequestPartName();
        } else if (ex instanceof HttpMessageNotReadableException unreadable) {
            for (Throwable cause = unreadable.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                    field = mapping.getPath().stream()
                            .map(ref -> ref.getFieldName() == null
                                    ? "[" + ref.getIndex() + "]" : ref.getFieldName())
                            .collect(Collectors.joining("."));
                    break;
                }
            }
        }
        // Do not echo Jackson messages, rejected values or multipart parser details.
        return validation(request, List.of(new ApiProblem.FieldError(field, "请求字段缺失或格式不正确")));
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
        if (ex instanceof ErrorResponse framework) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            ApiErrorCode code = switch (status) {
                case BAD_REQUEST -> ApiErrorCode.VALIDATION_FAILED;
                case NOT_FOUND -> ApiErrorCode.ROUTE_NOT_FOUND;
                case METHOD_NOT_ALLOWED -> ApiErrorCode.METHOD_NOT_ALLOWED;
                case NOT_ACCEPTABLE -> ApiErrorCode.NOT_ACCEPTABLE;
                case PAYLOAD_TOO_LARGE -> ApiErrorCode.PAYLOAD_TOO_LARGE;
                case UNSUPPORTED_MEDIA_TYPE -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
                default -> ApiErrorCode.INTERNAL_ERROR;
            };
            List<ApiProblem.FieldError> errors = code == ApiErrorCode.VALIDATION_FAILED
                    ? List.of(new ApiProblem.FieldError("$", "请求格式不正确")) : null;
            return ResponseEntity.status(status).headers(framework.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problemBody(status, code, status.getReasonPhrase(), request, errors));
        }
        if (ex instanceof MultipartException) {
            return validation(request, List.of(new ApiProblem.FieldError("$", "上传请求格式不正确")));
        }
        log.error("未预期异常：{} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "服务端内部错误", request, null);
    }

    private ResponseEntity<ApiProblem> validation(HttpServletRequest request, List<ApiProblem.FieldError> errors) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "请求参数校验未通过", request,
                errors.isEmpty() ? List.of(new ApiProblem.FieldError("$", "请求参数校验未通过")) : errors);
    }

    private static List<ApiProblem.FieldError> bindingErrors(BindingResult result) {
        List<ApiProblem.FieldError> errors = new ArrayList<>();
        result.getFieldErrors().forEach(error -> errors.add(
                new ApiProblem.FieldError(error.getField(), error.isBindingFailure()
                        ? "请求字段格式不正确" : safeMessage(error.getDefaultMessage()))));
        result.getGlobalErrors().forEach(error -> errors.add(
                new ApiProblem.FieldError("$", safeMessage(error.getDefaultMessage()))));
        return errors;
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "请求参数校验未通过" : message;
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null && !query.name().isEmpty()) {
            return query.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        return parameter.getParameterName() == null ? "$" : parameter.getParameterName();
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
            // 上传超限与格式不符已由「框架状态解析」那条分支覆盖（{@code MaxUploadSizeExceededException}
            // 与 {@code HttpMediaTypeNotSupportedException} 都是 ErrorResponse）；这两行是给**服务层**
            // 抛出的同名码用的——图片头嗅探否掉一个文件时，没有框架异常可依托。
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;

            // ── 实例级（M3 LIFE 启动门禁）──────────────────────────────────────
            // 503 而非 500：实例本身是好的，只是「还没准备好接业务」（启动自愈未跑完），
            // 稍后重试即可；500 的含义是「这一次请求出了意外」，两者对客户端是不同动作。
            case INSTANCE_NOT_READY -> HttpStatus.SERVICE_UNAVAILABLE;

            // ── 台账域（M2 LEDGER）──────────────────────────────────────────────
            // 逐项按契约登记，不按前缀猜：同一前缀里既有 404（马甲不存在）又有 409（编号冲突）。
            // 尚未落服务的码（COMMAND_*、REPLAY_*、MAP_CALIBRATION_IN_PROGRESS 等）继续走 default——
            // 落地那一刻再补进这张表，而不是现在替它们猜一个看着合理的状态。
            case DEVICE_VEST_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DEVICE_VEST_NUM_CONFLICT, DEVICE_VEST_ALREADY_BOUND, DEVICE_VEST_NOT_BOUND ->
                    HttpStatus.CONFLICT;

            // ── 硬件链路（HW-LINK，D06）──────────────────────────────────────────
            // 两个码同为 409、含义不同：「谁占着」——`DEVICE_LINK_ALREADY_OPEN` 是同一条逻辑链路的
            // 重复开启请求，`DEVICE_LINK_SERIAL_BUSY` 是物理资源冲突（串口被本进程另一条链路持有，
            // 或打开时被系统拒绝）。前端按 code 分流，不依赖 title 文案。
            case DEVICE_LINK_ALREADY_OPEN, DEVICE_LINK_SERIAL_BUSY -> HttpStatus.CONFLICT;

            // ── 地图域（M2 MAP-BASE）────────────────────────────────────────────
            // 底图台账：查不到是 404；被想定引用/演习在用是 409
            // （冲突是「现在不能删」，不是参数错误——契约 paths/map.yaml 的 deleteMap 即 409）。
            // 楼层点位与 UWB 标定：getUwbMeta 的「还没标定」是 404 MAP_NOT_CALIBRATED
            // （与 MAP_NOT_FOUND 分码，现场动作不同）；putUwbMeta 的校验失败是 400，
            // 契约把这两处原写成同一个码，本次按「一码一状态」拆开。
            case MAP_NOT_FOUND, MAP_NOT_CALIBRATED, MAP_IMAGE_MISSING -> HttpStatus.NOT_FOUND;
            case MAP_UWB_META_INCOMPLETE -> HttpStatus.BAD_REQUEST;
            case MAP_IN_USE -> HttpStatus.CONFLICT;

            // ── 想定域（M2 PLAN）────────────────────────────────────────────────
            // 与地图域同形：想定查不到是 404；正被进行中的演习占着（改/删/改编成）是 409。
            // 与 EXERCISE_PLAN_NOT_FOUND 分码：那个属开演语境，前端在「打开想定详情」
            // 这一条路径上拿到的应当是 PLAN_NOT_FOUND。
            case PLAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PLAN_IN_USE -> HttpStatus.CONFLICT;

            // ── 演习生命周期（M3 LIFE）──────────────────────────────────────────
            // 四个码同前缀、三种状态，故逐项登记：开演所引用的想定查不到是 404；想定编成为空是 400
            // （是这次请求的内容问题，不是「资源不存在」）；开演时已有在跑场次、停演时没有在跑场次
            // 都是 409——都是「当前状态不允许这个动作」，客户端处置动作相同（先 GET current 看真相）。
            case EXERCISE_PLAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EXERCISE_ROSTER_EMPTY -> HttpStatus.BAD_REQUEST;
            case EXERCISE_ALREADY_RUNNING, EXERCISE_NOT_RUNNING -> HttpStatus.CONFLICT;

            // ── 受保护文件（M2 MAP-BASE，D09-②）────────────────────────────────
            // 签名错与已过期共用一个码、一个状态：客户端处置动作相同（回详情接口取新 URL）。
            // 与 AUTH_TOKEN_INVALID 的 401 分属两个信任域，文件签名失效不该要求重新登录。
            case FILE_URL_INVALID -> HttpStatus.FORBIDDEN;

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
