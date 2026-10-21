package com.dualx.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 鉴权配置（{@code scds.auth.jwt.*}），落点见 docs/design/03-auth.md §4.3。
 *
 * <p><b>刻意与 {@code scds.deployment-mode} 分开</b>：前者是「环境」（CLOUD / PRIVATE），
 * 本类是「能力」（能不能签发）。二者正交——PRIVATE 完全可以只验签（令牌由运维在别处签好），
 * CLOUD 也完全可以在某客户的边缘实例上打开签发（离线演练）。禁止在代码里用环境反推能力。</p>
 *
 * <p>所有字段用包装类型 + 紧凑构造器补默认值，而不是靠 yml 里写全：漏配一项时应当
 * 得到一个明确默认值，而不是让整个上下文因绑定失败而起不来。</p>
 *
 * @param issuer          {@code iss} claim；PRIVATE 单机就用默认值
 * @param signingEnabled  本进程是否具备签发账号令牌的能力；false 时 login/refresh/logout 不注册
 * @param publicKey       验签公钥 PEM 内联文本（与 {@code publicKeyFile} 二选一）
 * @param publicKeyFile   验签公钥 PEM 文件路径（现场推荐，免去内联换行的转义麻烦）
 * @param privateKey      签名私钥 PEM 内联文本（仅 signingEnabled=true 且非自动生成时需要）
 * @param privateKeyFile  签名私钥 PEM 文件路径
 * @param jwksUri         云管 JWKS 端点；留空表示永不联网，只用本地公钥
 * @param jwksRefreshMs   JWKS 刷新间隔（仅配置了 {@code jwksUri} 时有意义）
 * @param accessTokenTtl  账号访问令牌有效期，契约建议 30 分钟
 * @param refreshTokenTtl 刷新令牌有效期，覆盖一次集训周期
 * @param deviceTokenTtl  设备令牌有效期，覆盖单日演习（设备令牌不该跨天存活）
 */
@ConfigurationProperties(prefix = "scds.auth.jwt")
public record AuthJwtProperties(
        String issuer,
        Boolean signingEnabled,
        String publicKey,
        String publicKeyFile,
        String privateKey,
        String privateKeyFile,
        String jwksUri,
        Long jwksRefreshMs,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration deviceTokenTtl) {

    public AuthJwtProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "scds-edge";
        }
        if (signingEnabled == null) {
            signingEnabled = Boolean.TRUE;
        }
        if (jwksRefreshMs == null || jwksRefreshMs <= 0) {
            jwksRefreshMs = 3_600_000L;
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(30);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(14);
        }
        if (deviceTokenTtl == null) {
            deviceTokenTtl = Duration.ofHours(12);
        }
    }
}
