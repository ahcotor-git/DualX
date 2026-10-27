package com.dualx.api.auth;

import com.dualx.api.dto.auth.DeviceLoginRequest;
import com.dualx.api.dto.auth.DeviceSelf;
import com.dualx.api.dto.auth.DeviceTokenPair;
import com.dualx.api.dto.auth.TokenPair;
import com.dualx.api.dto.auth.User;
import com.dualx.core.auth.AccountSession;
import com.dualx.core.auth.AuthService;
import com.dualx.core.auth.DeviceAuthService;
import com.dualx.core.auth.IssuedTokens;
import com.dualx.core.domain.Account;
import com.dualx.core.domain.Vest;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.state.SituationQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证接口中不随签发能力变化的部分：取自身信息与设备认证。
 *
 * <p>{@code GET /api/v1/auth/me} 只读，只验签不签发的实例也要能回答「我是谁」，因此本类
 * 不条件注册。签发端点 {@code login} / {@code refresh} / {@code logout} 在
 * {@link AccountAuthController}，它们只在 {@code signing-enabled=true} 时存在。</p>
 *
 * <p>{@code device/login} 与 {@code device/me} 都已接 {@link DeviceAuthService}（绑定密钥校验 +
 * SID 单端在线）：{@code me} 的身份来自台账、实时状态来自态势读侧，两者都不是本层自己的数据。</p>
 *
 * <p>包内共享的「服务结果 → 契约 DTO」映射也放在本类：它是包里唯一无条件存在的控制器，
 * 让条件注册的 {@link AccountAuthController} 反向依赖它，方向才稳定。</p>
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final DeviceAuthService deviceAuthService;
    private final VestLedgerService vestLedger;
    private final SituationQuery situations;

    public AuthController(AuthService authService, DeviceAuthService deviceAuthService,
                          VestLedgerService vestLedger, SituationQuery situations) {
        this.authService = authService;
        this.deviceAuthService = deviceAuthService;
        this.vestLedger = vestLedger;
        this.situations = situations;
    }

    /** 获取当前登录用户。 */
    @GetMapping("/api/v1/auth/me")
    public User getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return toUser(authService.me(accountId(jwt)));
    }

    /** 单兵终端登录：马甲编号 + 绑定密钥换取设备令牌。 */
    @PostMapping("/api/v1/auth/device/login")
    public DeviceTokenPair deviceLogin(@RequestBody @Valid DeviceLoginRequest request,
                                      HttpServletRequest httpRequest) {
        return toDeviceTokenPair(deviceAuthService.login(request.vestNum(), request.secret(),
                httpRequest.getRemoteAddr()));
    }

    /**
     * 单兵终端获取自身信息与实时状态。
     *
     * <p>顺序有意义：先校 SID 单端在线，再读台账。换绑 / 退役会把 {@code device_session_sid}
     * 置 NULL，于是「已退役但令牌还在手上」在第一步就是 401，不会先掉进 404 分支；
     * 反过来先读台账会让退役马甲拿到一个语义错误的 404。</p>
     *
     * <p>{@code state} 是契约里唯一允许为空的部分：无演习进行中、或本马甲不在本场编成内即为空。
     * 身份三项（编号 / 名称 / 队伍）来自台账，只要令牌在有效期且会话有效就一直有。</p>
     */
    @GetMapping("/api/v1/auth/device/me")
    public DeviceSelf getDeviceSelf(@AuthenticationPrincipal Jwt jwt) {
        DeviceTokenClaims claims = deviceClaims(jwt);
        deviceAuthService.requireActiveSession(claims);
        Vest vest = vestLedger.get(claims.vestNum());
        return new DeviceSelf(vest.vestNum(), vest.name(), vest.team(),
                situations.unit(claims.vestNum()).orElse(null));
    }

    /**
     * 从已认证令牌取账号主键。
     *
     * <p>{@code sub} 必须能还原成账号 UUID：本实例签发的账号令牌里它就是 {@code accountId}。
     * 解析不出来说明令牌不是本实例签发的账号令牌——归为令牌不成立（401），不当作服务端缺陷。</p>
     */
    static UUID accountId(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "令牌主体不是有效账号");
        }
    }

    /**
     * 从已认证的设备令牌取设备身份：{@code sub} 是马甲编号、{@code sid} 是会话 ID。
     *
     * <p>两者缺一或形状不对，说明这不是本实例签发的设备令牌（或签发时就不完整）——
     * 归为令牌不成立（401），不当作服务端缺陷。{@code sid} 必须解析成 UUID 才能与
     * {@code t_vest.device_session_sid} 的存法一致地比对。</p>
     */
    static DeviceTokenClaims deviceClaims(Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "设备令牌主体不成立");
        }
        try {
            return new DeviceTokenClaims(Integer.parseInt(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("sid")));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_INVALID, "设备令牌主体不成立");
        }
    }

    /** 核心侧身份快照 → 契约 {@code User}。 */
    static User toUser(AccountSession session) {
        Account account = session.account();
        return new User(account.id().toString(), account.username(), account.displayName(),
                session.roles(), session.permissions(), account.lastLoginAt());
    }

    /** 核心侧签发结果 → 契约 {@code TokenPair}。 */
    static TokenPair toTokenPair(IssuedTokens issued) {
        return new TokenPair(issued.accessToken(), issued.refreshToken(), issued.expiresIn(),
                toUser(issued.session()));
    }

    /** 核心侧设备登录结果 → 契约 {@code DeviceTokenPair}。 */
    static DeviceTokenPair toDeviceTokenPair(DeviceAuthService.IssuedDeviceToken issued) {
        return new DeviceTokenPair(issued.deviceToken(), issued.expiresIn());
    }
}
