package com.dualx.api.security;

import com.dualx.core.domain.Role;
import com.dualx.core.port.AccessTokenClaims;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.RefreshTokenClaims;
import com.dualx.core.port.TokenSigner;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * RS256 令牌签发器（设计 03 §4.2 / §4.3.2），{@link TokenSigner} 的本进程实现。
 *
 * <p>落在 {@code scds-api}：签名依赖 nimbus 与 Spring Security，与「接口适配层的基础设施」
 * 同层。{@code scds-core} 只依赖 {@code scds-domain} 里的端口，因此不反向耦合到这一层——
 * 装配发生在运行期，编译期方向仍是 core ← api。</p>
 *
 * <p><b>刻意不设 {@code kid}。</b>本进程既是签发者又是验签者，而本地公钥验签路径
 * （{@code JwtDecoderConfig#decoderFor}）构造的 JWK 也不带 {@code kid}。若这里写上
 * {@code kid}，验签侧的 {@code DefaultJWKMatcher} 会用它去比对那把无 {@code kid} 的 JWK，
 * 结果是一把匹配不上的密钥 → **自己签的令牌自己验不过**。需要「新旧公钥并存、平滑轮换」
 * 时才引入 {@code jwks-uri}，那时两边一起补 {@code kid}。</p>
 *
 * <p><b>{@code signing-enabled=false} 时不读私钥。</b>「只验签不签发」的实例（令牌由运维
 * 在别处签好）本来就没有私钥，若在构造期无条件去读，会表现为「配了能力开关反而起不来」。
 * 这类实例上三个签发端点不注册（见 {@code AccountAuthController} 的 {@code @ConditionalOnProperty}），
 * 所以本类的方法不会被调到。</p>
 */
@Component
public class RsaTokenSigner implements TokenSigner {

    private static final Logger log = LoggerFactory.getLogger(RsaTokenSigner.class);

    /** 账号令牌用途：指挥台与单兵 App 的业务接口、WS 握手都认它。 */
    private static final String AUD_ACCOUNT = "scds-edge";

    /** 刷新令牌用途：只有 {@code /auth/refresh} 认它。 */
    private static final String AUD_REFRESH = "scds-refresh";

    /** 设备令牌用途：只有设备自己的端点认它（+ {@code situation/vests/**}）。 */
    private static final String AUD_DEVICE = "scds-device";

    private final AuthJwtProperties properties;

    /** 无签发能力时为 null；此时本类只作为「能力查询」存在。 */
    private final JWSSigner jwsSigner;

    public RsaTokenSigner(AuthJwtProperties properties) {
        this.properties = properties;
        if (properties.signingEnabled()) {
            RSAPrivateKey key = PemKeys.readPrivateKey(properties);
            this.jwsSigner = new RSASSASigner(key);
            log.info("账号令牌签发已启用：iss={}，密钥 {} 位，access/refresh/device 有效期 {} / {} / {}",
                    properties.issuer(), key.getModulus().bitLength(),
                    properties.accessTokenTtl(), properties.refreshTokenTtl(), properties.deviceTokenTtl());
        } else {
            this.jwsSigner = null;
            log.info("账号令牌签发已禁用（scds.auth.jwt.signing-enabled=false）：本进程只验签，"
                    + "login/refresh/logout 不注册（404）");
        }
    }

    @Override
    public boolean enabled() {
        return properties.signingEnabled();
    }

    @Override
    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    @Override
    public Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    @Override
    public Duration deviceTokenTtl() {
        return properties.deviceTokenTtl();
    }

    @Override
    public String signAccessToken(AccessTokenClaims claims) {
        return sign(base(claims.accountId().toString(), AUD_ACCOUNT, accessTokenTtl())
                // jti 只为日志串联：accessToken 不可撤销，没有库行需要它去对应。
                .jwtID(UuidV7.generate().toString())
                // 写角色编码而非权限点：权限点随版本变化，冻进 30 分钟的令牌里就要等它过期
                // 才生效；角色变化极少，且变了本来就该重登（设计 03 §4.5）。
                .claim("roles", claims.roles().stream().map(Role::name).toList())
                .build());
    }

    @Override
    public String signRefreshToken(RefreshTokenClaims claims) {
        // jti 用调用方给的 tokenId：它就是 t_refresh_token.id，必须先在库里留下痕迹，
        // 令牌才允许离开本进程；否则会出现一段「已签发但查不到、因而无法撤销」的窗口。
        return sign(base(claims.accountId().toString(), AUD_REFRESH, refreshTokenTtl())
                .jwtID(claims.tokenId().toString())
                .build());
    }

    @Override
    public String signDeviceToken(DeviceTokenClaims claims) {
        return sign(base(String.valueOf(claims.vestNum()), AUD_DEVICE, deviceTokenTtl())
                .jwtID(UuidV7.generate().toString())
                // sid 是「单端在线」的全部依据：校验时拿它与 t_vest.device_session_sid 比对，
                // 不等即判旧令牌（设计 03 §4.9）。
                .claim("sid", claims.sessionId().toString())
                .build());
    }

    private JWTClaimsSet.Builder base(String subject, String audience, Duration ttl) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)));
    }

    private String sign(JWTClaimsSet claims) {
        if (jwsSigner == null) {
            // 装配期就该被拦住：signing-enabled=false 时三个签发端点不注册，正常走不到这里。
            throw new IllegalStateException(
                    "本进程不具备签发能力（scds.auth.jwt.signing-enabled=false），不应调用签发");
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(jwsSigner);
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
        return jwt.serialize();
    }
}
