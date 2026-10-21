package com.dualx.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 本地鉴权配置（{@code scds.auth.jwt.*}），部署边界见 docs/architecture.md 第0节。
 *
 * <p>账号与设备均由本地业务实例签发、验签；云端仅反向代理。
 * {@code deployment-mode}是历史兼容登记，不能反推签发能力或是否启用FRPC。
 * 远端JWKS配置失败关闭，避免本地鉴权意外依赖外网。</p>
 *
 * <p>所有字段用包装类型 + 紧凑构造器补默认值，而不是靠 yml 里写全：漏配一项时应当
 * 得到一个明确默认值，而不是让整个上下文因绑定失败而起不来。</p>
 *
 * <p>但「显式配成 0 或负数」是另一回事：那是配置错误，必须在校验期直接拒绝。
 * 静默回落到默认值会让现场表现为「登录后令牌立刻过期」这类极难定位的故障（review R27）。</p>
 *
 * @param issuer          {@code iss} claim；生产每客户显式配置
 * @param signingEnabled  本地三种用途的签发能力；false保留测试/工具用途
 * @param publicKey       验签公钥 PEM 内联文本（与 {@code publicKeyFile} 二选一）
 * @param publicKeyFile   验签公钥 PEM 文件路径（现场推荐，免去内联换行的转义麻烦）
 * @param privateKey      签名私钥 PEM 内联文本（仅 signingEnabled=true 且非自动生成时需要）
 * @param privateKeyFile  签名私钥 PEM 文件路径
 * @param jwksUri         历史兼容键，必须为空；非空拒绝，验签只用本地公钥
 * @param jwksRefreshMs   历史兼容字段，本地PEM路径不使用
 * @param accessTokenTtl  账号访问令牌有效期，契约建议 30 分钟
 * @param refreshTokenTtl 刷新令牌有效期，覆盖一次集训周期
 * @param deviceTokenTtl  设备令牌从签发起的有效期，不按日历午夜截断
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
        if (jwksUri != null && !jwksUri.isBlank()) {
            throw new IllegalArgumentException(
                    "Local deployment requires PEM keys; remote JWKS is not supported.");
        }
        jwksUri = "";
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
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
        requirePositive(deviceTokenTtl, "device-token-ttl");
        // refresh 必须严格长于 access：滚动换发依赖「access 先过期、refresh 还有效」这个窗口，
        // 两者相等或倒挂时前端要么没有换发窗口，要么拿得到一个比它自己的续命凭证还短命的 access。
        if (refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalArgumentException(
                    "scds.auth.jwt.refresh-token-ttl must outlast access-token-ttl.");
        }
    }

    /**
     * 显式配置的值必须为正。只对「配了但配错」报错；{@code null}（漏配）已在上方补默认值，
     * 走不到这里。报错只带属性名，不回显配置值。
     */
    private static void requirePositive(Duration ttl, String key) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("scds.auth.jwt." + key + " must be positive.");
        }
    }
}
