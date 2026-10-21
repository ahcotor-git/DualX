package com.dualx.api.security;

import com.dualx.api.error.ApiErrorCode;
import com.dualx.api.error.ApiProblem;
import com.dualx.api.support.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 Security 过滤器链内部把失败写回契约形状的 {@code Problem}。
 *
 * <p>为什么不能复用 {@code ApiExceptionHandler}：那个 {@code @RestControllerAdvice}
 * 只在 DispatcherServlet 之内生效，而认证/授权失败发生在它**之前**，异常根本到不了
 * 控制器层。两条出口（401 / 403）因此必须各自显式写响应体。</p>
 *
 * <p>此处刻意直接写 {@code Problem} 而不经过 {@code ResponseEntity}：过滤器链里没有
 * 消息转换器可借，自己序列化是最少依赖的做法。</p>
 */
final class ApiProblemWriter {

    private ApiProblemWriter() {
    }

    static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, ApiErrorCode code, String title, String detail) throws IOException {
        // 与 ApiExceptionHandler 取同一个 attribute：TraceIdFilter 已排到最高优先级，
        // 因此即便是安全链抛出的早期失败，这里也能拿到 traceId（否则这两个响应会变成
        // 「没有追踪号的孤立错误」，恰是现场报障最需要对日志的场景）。
        Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        ApiProblem problem = new ApiProblem(ApiProblem.TYPE_ABOUT_BLANK, title, status.value(), code,
                detail, request.getRequestURI(), traceId == null ? null : traceId.toString(), null);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
