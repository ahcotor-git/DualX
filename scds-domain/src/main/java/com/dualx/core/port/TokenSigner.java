package com.dualx.core.port;

import com.dualx.core.domain.Role;

import java.time.Duration;

/**
 * 令牌签发端口（设计 03 §4.3.2）。核心侧只描述「要签什么」，算法与密钥细节留在适配器。
 *
 * <p>实现落在 {@code scds-api}（{@code RsaTokenSigner}）：签名依赖 Spring Security 与 nimbus，
 * 与「接口适配层的基础设施」同层。若日后拆出 {@code scds-infra}，把实现迁过去即可，
 * 本端口不动。</p>
 *
 * <p><b>能力开关不是环境开关</b>：{@link #enabled()} 为 {@code false} 时，
 * {@code POST /api/v1/auth/login|refresh|logout} 一律不注册（404）。是否具备签发能力
 * 与「这台机器是 CLOUD 还是 PRIVATE」正交——PRIVATE 可以只验签（令牌由运维在别处签好），
 * CLOUD 也可以在客户的边缘上打开签发（离线演练）。禁止用 {@code deployment-mode} 反推本方法。</p>
 *
 * <p><b>为什么有效期也在端口上</b>：调用方要把它写进两处——API 响应的
 * {@code TokenPair.expiresIn}（剩余有效秒数）与 {@code t_refresh_token.expires_at}。
 * 若让核心侧自己读配置，就得把 {@code scds-api} 的配置类下沉到 {@code scds-domain}，
 * 或把同一个前缀绑定两次。三处取值必须同源，故随端口一起暴露。</p>
 */
public interface TokenSigner {

    /** 本进程是否具备签发账号 accessToken 的能力。 */
    boolean enabled();

    /** 账号 accessToken 有效期（设计 03 §4.2 定为 30 分钟）。 */
    Duration accessTokenTtl();

    /** 账号 refreshToken 有效期（14 天，覆盖一次集训周期）。 */
    Duration refreshTokenTtl();

    /** 设备令牌有效期（12 小时，不跨天）。 */
    Duration deviceTokenTtl();

    /**
     * 签发账号 accessToken（{@code aud = scds-edge}）。
     *
     * <p>{@code iss} / {@code iat} / {@code nbf} / {@code exp} / {@code jti} 由实现按自身时钟与本端口
     * 声明的有效期补齐；调用方只需给出身份与角色。{@code jti} 只为日志串联，调用方无需感知。</p>
     */
    String signAccessToken(AccessTokenClaims claims);

    /**
     * 签发账号 refreshToken（{@code aud = scds-refresh}）。
     *
     * <p>与 accessToken 不同，它的 {@code jti} 由调用方指定——该值就是
     * {@code t_refresh_token.id}（主键），必须在令牌离开本进程之前就已落库，
     * 否则会出现「已签发但查不到」的窗口期，那段时间无法撤销。</p>
     */
    String signRefreshToken(RefreshTokenClaims claims);

    /** 签发设备令牌（{@code aud = scds-device}）。 */
    String signDeviceToken(DeviceTokenClaims claims);
}
