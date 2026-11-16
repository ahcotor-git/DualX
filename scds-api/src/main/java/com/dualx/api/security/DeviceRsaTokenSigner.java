package com.dualx.api.security;

import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.DeviceTokenSigner;
import com.dualx.core.support.UuidV7;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * RS256 设备令牌签发器（设计 03b §3 / §10.2），{@link DeviceTokenSigner} 的本进程实现。
 *
 * <p>与账号签发器（{@link RsaTokenSigner}）<b>刻意分开</b>：独立 issuer、独立公私钥、独立开关。
 * 设备业务只依赖 {@link DeviceTokenSigner} 端口，缺设备私钥不得借账号私钥顶替（§3）。</p>
 *
 * <p><b>{@code signing-enabled=false} 时不读设备私钥。</b>只验签设备令牌的实例（保持开关为假并显式配置
 * issuer / 公钥）本来就没有设备私钥；若构造期无条件去读，会表现为「配了开关反而起不来」。这类实例上
 * {@code device/login} 不注册（404），本类方法不会被调到。</p>
 *
 * <p><b>装配期失败关闭（§3）。</b>设备签发启用时，除 {@link DeviceJwtProperties} 已做的「键是否给到」之外，
 * 本类在构造期补三项需要读取密钥材料、或需同时看到账号配置才能判定的校验：① 设备公私钥<b>配对</b>；
 * ② 设备 RSA <b>强度</b> ≥ 2048；③ 设备 issuer 与公钥<b>均不得与账号相同</b>。任一不满足即启动失败——
 * 让「把账号密钥/身份误配成设备用途」这类配置在开机时就响亮失败，而不是等到第一次设备登录
 * 才发现「设备令牌竟是用账号私钥签的」。</p>
 */
@Component
public class DeviceRsaTokenSigner implements DeviceTokenSigner {

    private static final Logger log = LoggerFactory.getLogger(DeviceRsaTokenSigner.class);

    /** 设备令牌用途：只有设备自己的端点认它（+ {@code situation/vests/**}）。 */
    private static final String AUD_DEVICE = "scds-device";

    /** RSA 最小模数位数（03b §2：算法保持 RS256，RSA 至少 2048 位）。 */
    private static final int MIN_RSA_BITS = 2048;

    private final DeviceJwtProperties properties;

    /** 无签发能力时为 null；此时本类只作为「能力查询」存在。 */
    private final JWSSigner jwsSigner;

    public DeviceRsaTokenSigner(DeviceJwtProperties properties, AuthJwtProperties accountProperties) {
        this.properties = properties;
        if (properties.signingEnabled()) {
            RSAPrivateKey privateKey = PemKeys.readPrivateKey(new PemKeys.Source(
                    properties.privateKey(), properties.privateKeyFile(),
                    "SCDS_DEVICE_JWT_PRIVATE_KEY", "SCDS_DEVICE_JWT_PRIVATE_KEY_FILE"));
            RSAPublicKey publicKey = PemKeys.readPublicKey(new PemKeys.Source(
                    properties.publicKey(), properties.publicKeyFile(),
                    "SCDS_DEVICE_JWT_PUBLIC_KEY", "SCDS_DEVICE_JWT_PUBLIC_KEY_FILE"));
            requireKeyPair(privateKey, publicKey);
            requireStrength(privateKey);
            requireDistinctFromAccount(properties, accountProperties, publicKey);

            this.jwsSigner = new RSASSASigner(privateKey);
            log.info("设备令牌签发已启用：iss={}，密钥 {} 位，有效期 {}",
                    properties.issuer(), privateKey.getModulus().bitLength(), properties.deviceTokenTtl());
        } else {
            this.jwsSigner = null;
            log.info("设备令牌签发已禁用（scds.auth.jwt.device.signing-enabled=false）："
                    + "device/login 不注册（404），本进程不读取设备私钥");
        }
    }

    @Override
    public boolean enabled() {
        return properties.signingEnabled();
    }

    @Override
    public Duration deviceTokenTtl() {
        return properties.deviceTokenTtl();
    }

    @Override
    public String signDeviceToken(DeviceTokenClaims claims) {
        if (jwsSigner == null) {
            // 装配期就该被拦住：signing-enabled=false 时 device/login 不注册，正常走不到这里。
            throw new IllegalStateException(
                    "本进程不具备设备签发能力（scds.auth.jwt.device.signing-enabled=false），不应调用签发");
        }
        Instant now = Instant.now();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(claims.vestNum()))
                .audience(AUD_DEVICE)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(deviceTokenTtl())))
                // jti 只为日志串联，但校验期要求它是字符串（JwtDecoderConfig#validateRawClaims）。
                .jwtID(UuidV7.generate().toString())
                // sid 是「单端在线」的全部依据：校验时拿它与 t_vest.device_session_sid 比对（03b §6）。
                .claim("sid", claims.sessionId().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet);
        try {
            jwt.sign(jwsSigner);
        } catch (JOSEException ex) {
            throw new IllegalStateException("设备 JWT 签名失败", ex);
        }
        return jwt.serialize();
    }

    /**
     * 公钥与私钥必须来自同一对密钥。
     *
     * <p>用模数比对而非「自签自验一次」：RSA 的模数唯一标识一对密钥，指数几乎恒为 65537，
     * 模数相等即配对；而自签自验要真的产生一次签名，构造期做这件事只是徒增启动开销。</p>
     */
    private static void requireKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException(
                    "scds.auth.jwt.device 的公钥与私钥不是同一对（模数不一致）："
                            + "请核对 device.public-key(-file) 与 device.private-key(-file)（03b §3）");
        }
    }

    private static void requireStrength(RSAPrivateKey privateKey) {
        int bits = privateKey.getModulus().bitLength();
        if (bits < MIN_RSA_BITS) {
            throw new IllegalStateException(
                    "scds.auth.jwt.device 密钥强度不足：" + bits + " 位，要求至少 " + MIN_RSA_BITS + " 位（03b §2）");
        }
    }

    /**
     * 设备用途的 issuer 与公钥**均不得**与账号用途相同（03b §3）。
     *
     * <p>本类刻意一并注入账号配置：这两项是<b>跨配置不变量</b>，单条 {@code @ConfigurationProperties}
     * 记录只能看到自己，判不出「与账号相同」。账号 issuer 有默认值（{@code scds-edge}），因此这里
     * 不会因账号漏配 issuer 而误判；账号公钥是账号验签器的必需项（{@link JwtDecoderConfig} 无公钥即
     * 启动失败），故读取它不会引入新的失败面。</p>
     */
    private static void requireDistinctFromAccount(DeviceJwtProperties device,
                                                   AuthJwtProperties account,
                                                   RSAPublicKey devicePublicKey) {
        if (Objects.equals(device.issuer(), account.issuer())) {
            throw new IllegalStateException(
                    "scds.auth.jwt.device.issuer 不得与账号 issuer 相同（03b §3）："
                            + "设备用途必须使用独立身份，如需共用请重新评估部署形态");
        }
        RSAPublicKey accountPublicKey = PemKeys.readPublicKey(account);
        if (PemKeys.fingerprint(devicePublicKey).equals(PemKeys.fingerprint(accountPublicKey))) {
            throw new IllegalStateException(
                    "scds.auth.jwt.device 公钥不得与账号公钥相同（03b §3）："
                            + "账号与设备必须使用不同密钥对，不得复用账号密钥签发设备令牌");
        }
    }
}
