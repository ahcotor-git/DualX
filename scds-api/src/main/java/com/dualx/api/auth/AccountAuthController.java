package com.dualx.api.auth;

import com.dualx.api.dto.auth.LoginRequest;
import com.dualx.api.dto.auth.RefreshRequest;
import com.dualx.api.dto.auth.TokenPair;
import com.dualx.core.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号签发端点：{@code login} / {@code refresh} / {@code logout}（设计 03 §4.3.2、§4.4、Q1）。
 *
 * <p><b>整类条件注册。</b>{@code scds.auth.jwt.signing-enabled=false} 的实例只验签不签发，
 * 这三个端点**不注册**（客户端得到 404），而不是注册后返回 501——后者会让「本实例没有这个能力」
 * 与「接口尚未实现」这两种完全不同的情况在响应上长得一样，客户端无法据此决定是否该换，
 * baseUrl。{@code matchIfMissing=true} 让配置缺省时按可签发处理，与
 * {@link com.dualx.api.security.AuthJwtProperties} 的默认值保持一致。</p>
 *
 * <p><b>为什么 {@code /api/v1/auth/me} 不在这里：</b>它是只读接口，只验签不签发的实例同样要能
 * 回答「我是谁」，因此留在不条件注册的 {@link AuthController}。</p>
 *
 * <p>本类不做任何校验与令牌处理——那些是 {@link AuthService} 的职责；这里只负责
 * 「HTTP 请求 → 服务调用 → 契约 DTO」的翻译。</p>
 */
@RestController
@ConditionalOnProperty(prefix = "scds.auth.jwt", name = "signing-enabled",
        havingValue = "true", matchIfMissing = true)
public class AccountAuthController {

    private final AuthService authService;

    public AccountAuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 账号登录：发放 access/refresh 令牌。 */
    @PostMapping("/api/v1/auth/login")
    public TokenPair login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest) {
        // 限流口径只用 remoteAddr。未建立可信代理链前不读 X-Forwarded-For：
        // 任意客户端都能伪造该头，用它做限流等于把限流键交给攻击者（设计 03 §4.10）。
        return AuthController.toTokenPair(
                authService.login(request.username(), request.password(), httpRequest.getRemoteAddr()));
    }

    /** 用 refreshToken 滚动换取新的 access/refresh 令牌对。 */
    @PostMapping("/api/v1/auth/refresh")
    public TokenPair refreshToken(@RequestBody @Valid RefreshRequest request) {
        return AuthController.toTokenPair(authService.refresh(request.refreshToken()));
    }

    /** 退出登录：按账号令牌的 sub 撤销该账号全部 refreshToken（全端退出）。 */
    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logout(AuthController.accountId(jwt));
    }
}
