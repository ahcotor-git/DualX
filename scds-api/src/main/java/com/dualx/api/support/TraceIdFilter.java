package com.dualx.api.support;

import com.dualx.core.support.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路追踪号，即契约 {@code Problem.traceId} 的来源。
 *
 * <p>若上游（云管 / 反向代理）已带 {@code X-Trace-Id} 则沿用，保证跨段可串；否则本机用
 * UUIDv7 生成。写入 MDC 供日志、写回响应头供前端在报障时回传。</p>
 *
 * <p><b>顺序是硬要求：必须排在 Spring Security 之前</b>。安全过滤器的 order 是 -100，
 * 而 401/403 由它在 DispatcherServlet <b>之前</b>直接写出，{@code ApiExceptionHandler}
 * 根本不会介入。若本过滤器排在安全链之后，那两类响应取不到 traceId，恰好是报障时
 * 最需要 traceId 去对日志的场景。因此显式置于最高优先级。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "scds.traceId";

    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UuidV7.generate().toString();
        }
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
