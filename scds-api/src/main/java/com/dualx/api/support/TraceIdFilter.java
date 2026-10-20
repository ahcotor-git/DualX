package com.dualx.api.support;

import com.dualx.core.support.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路追踪号，即契约 {@code Problem.traceId} 的来源。
 *
 * <p>若上游（云管 / 反向代理）已带 {@code X-Trace-Id} 则沿用，保证跨段可串；否则本机用
 * UUIDv7 生成。写入 MDC 供日志、写回响应头供前端在报障时回传。</p>
 */
@Component
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
