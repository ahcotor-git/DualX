package com.dualx.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 设备用途配置（{@code scds.auth.jwt.device.*}），设计 03b §3。
 *
 * <p>与账号用途（{@link AuthJwtProperties}）<b>完全独立</b>：独立 issuer、独立公私钥、独立开关。
 * 设备配置<b>不回退</b>账号配置——缺设备私钥不得借账号私钥签发设备令牌（§3）。</p>
 *
 * <p>设备签发开关 {@code signingEnabled} 默认 <b>false</b>：目标形态是「云端账号签发 / 边缘设备签发」，
 * 边缘默认只签发设备令牌；但本类不假定部署形态，必须显式开启，且<b>不继承</b>账号 signing-enabled
 * （§3）。只验签设备令牌而不签发的实例，保持 {@code signingEnabled=false} 并显式配置设备 issuer / 公钥。</p>
 *
 * <p>所有字段用包装类型 + 紧凑构造器补默认值：漏配得到明确默认值，而不是让上下文因绑定失败而起不来。
 * 「显式配成 0 或负数」是配置错误，直接拒绝（同 {@link AuthJwtProperties}）。</p>
 *
 * <p><b>本类只做不依赖密钥材料的校验</b>：开关默认、TTL 为正、启用时 issuer 与公私钥是否<em>给到</em>。
 * 「公私钥配对 / RSA 强度不足」「设备 issuer / 公钥与账号相同」两项需要 {@code PemKeys} 参数化并同时
 * 看到账号配置，落在 03b §10.2 工作包 3a 的 {@code DeviceRsaTokenSigner} 装配期校验
 * （§10.1 允许按「≤3 文件」上限再拆；本包不引入任何读取设备私钥的代码，故无物可校）。</p>
 *
 * @param issuer          设备令牌 {@code iss} claim（启用签发时必须显式配置，且不得与账号相同）
 * @param signingEnabled  本进程是否签发设备令牌；默认 false，必须显式启用
 * @param publicKey       验签设备公钥 PEM 内联文本（与 {@code publicKeyFile} 二选一）
 * @param publicKeyFile   验签设备公钥 PEM 文件路径（现场推荐）
 * @param privateKey      签名设备私钥 PEM 内联文本（仅 signingEnabled=true 时需要）
 * @param privateKeyFile  签名设备私钥 PEM 文件路径
 * @param deviceTokenTtl  设备令牌从签发起的有效期，默认 12 小时，不按日历午夜截断
 */
@ConfigurationProperties(prefix = "scds.auth.jwt.device")
public record DeviceJwtProperties(
        String issuer,
        Boolean signingEnabled,
        String publicKey,
        String publicKeyFile,
        String privateKey,
        String privateKeyFile,
        Duration deviceTokenTtl) {

    public DeviceJwtProperties {
        if (signingEnabled == null) {
            signingEnabled = Boolean.FALSE;
        }
        if (deviceTokenTtl == null) {
            deviceTokenTtl = Duration.ofHours(12);
        }
        requirePositive(deviceTokenTtl, "device-token-ttl");
        // 未启用签发的实例（缺省）允许整段缺席，因此存在性校验只在显式启用时施加。
        if (signingEnabled) {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException(
                        "scds.auth.jwt.device.issuer is required when signing-enabled=true.");
            }
            requireKeySource(publicKey, publicKeyFile, "public-key");
            requireKeySource(privateKey, privateKeyFile, "private-key");
        }
    }

    /**
     * 显式配置的值必须为正。只对「配了但配错」报错；{@code null}（漏配）已在上方补默认值，
     * 走不到这里。报错只带属性名，不回显配置值。
     */
    private static void requirePositive(Duration ttl, String key) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("scds.auth.jwt.device." + key + " must be positive.");
        }
    }

    /** 内联文本与文件路径至少给一个；报错只带属性名，不回显配置值。 */
    private static void requireKeySource(String inline, String file, String key) {
        boolean hasInline = inline != null && !inline.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        if (!hasInline && !hasFile) {
            throw new IllegalArgumentException(
                    "scds.auth.jwt.device." + key + " (or " + key + "-file) is required when signing-enabled=true.");
        }
    }
}
