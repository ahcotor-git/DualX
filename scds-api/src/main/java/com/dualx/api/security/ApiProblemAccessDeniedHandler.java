package com.dualx.api.security;

import com.dualx.core.error.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 403 出口：把「已认证但无权」写成契约的 {@code Problem}（设计 03 §4.6）。
 *
 * <p>与 401 的分工是本设计里唯一的横向越权防线：用**设备令牌**去调指挥台接口，
 * 令牌本身是有效签发的（能通过验签与时间校验），只是 {@code aud} 不对用途——
 * 这必须报 403 {@code AUTH_FORBIDDEN} 而不是 401。若混成 401，客户端会以为
 * 「令牌过期了」而去反复刷新，永远刷不出一个能用的令牌。</p>
 */
@Component
public class ApiProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiProblemAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public ApiProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.debug("授权失败：{} {}（{}）", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());
        ApiProblemWriter.write(objectMapper, request, response, HttpStatus.FORBIDDEN,
                ApiErrorCode.AUTH_FORBIDDEN, "无权访问该资源",
                "令牌有效，但其角色或用途（aud）不允许访问该资源");
    }
}
