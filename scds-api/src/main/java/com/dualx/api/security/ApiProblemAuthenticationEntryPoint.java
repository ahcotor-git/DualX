package com.dualx.api.security;

import com.dualx.api.error.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 出口：把「没通过认证」写成契约的 {@code Problem}（设计 03 §4.6）。
 *
 * <p>不换掉这个出口的后果很具体：Spring Security 默认写空响应体或 Boot 的 error JSON，
 * 于是同一套前端要维护两种错误解析逻辑，而这两种响应**都没有 traceId**——
 * 现场报障时无法把「这个 401」与日志里的某一行对上。</p>
 */
@Component
public class ApiProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiProblemAuthenticationEntryPoint.class);

    /**
     * Spring Security 6.5 里**没有** {@code JwtExpiredException} 这个类型：过期由
     * {@code JwtTimestampValidator} 报成一个
     * {@code OAuth2Error(code="invalid_token", description="Jwt expired at <时刻>")}，
     * 与「签名错」「aud 不认」共用同一个 error code，只有 description 不同。
     * 因此这里只能按 description 前缀判别——这是本类唯一的脆弱点。
     *
     * <p>兜底办法：{@code ApiProblemSecurityEntryPointTest} 用真实过期令牌跑通这条分支，
     * 升级 Spring Security 后若文案变化，测试会红，而不是静默退化成「一律 AUTH_TOKEN_INVALID」。
     * 若哪天该文案真的改了，改这一个常量即可。</p>
     */
    static final String EXPIRED_DESCRIPTION_PREFIX = "Jwt expired";

    private final ObjectMapper objectMapper;

    public ApiProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        boolean expired = isExpired(authException);
        ApiErrorCode code = expired ? ApiErrorCode.AUTH_TOKEN_EXPIRED : ApiErrorCode.AUTH_TOKEN_INVALID;
        log.debug("认证失败：{} {}（{}）→ {}", request.getMethod(), request.getRequestURI(),
                authException.getMessage(), code);
        ApiProblemWriter.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, code,
                expired ? "登录状态已过期，请重新登录" : "未认证或令牌无效",
                expired ? "访问令牌已超过 exp，请用 refreshToken 换新令牌或重新登录"
                        : "缺少 Authorization: Bearer 头，或令牌签名/格式/aud 不被本实例接受");
    }

    private static boolean isExpired(AuthenticationException authException) {
        for (Throwable cause = authException; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validationException) {
                for (OAuth2Error error : validationException.getErrors()) {
                    String description = error.getDescription();
                    if (description != null && description.startsWith(EXPIRED_DESCRIPTION_PREFIX)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
