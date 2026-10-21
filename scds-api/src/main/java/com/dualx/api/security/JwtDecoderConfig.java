package com.dualx.api.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.dualx.api.error.ApiErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;

/**
 * 令牌验签器装配（设计 03 §4.3）。
 *
 * <p><b>禁用 {@code spring.security.oauth2.resourceserver.jwt.*} 自动装配</b>，改为自建
 * {@link JwtDecoder}。理由：自动装配在 {@code issuer-uri} 形态下会做一次 OIDC 发现，
 * 这是**启动期**的联网请求——现场无外网时直接表现为「服务起不来」，而失败原因还是
 * 一条与业务无关的 DNS/超时错误。自建 decoder 后，公钥来源是本文件里显式的一条分支，
 * 无外网、无 DNS 也能启动并验签。</p>
 *
 * <p>认证与授权的分工：本类只解决「这个令牌是不是本系统签发的、是否在有效期内」；
 * 「这个令牌能不能访问这个资源」由 {@code SecurityConfig} 的 aud 规则决定。
 * 因此这里对 {@code aud} 只校验「属于已知用途集合」，不限定具体某一种——
 * 设备令牌访问指挥台接口必须是 403（已认证但无权），而不是 401（令牌不成立）。</p>
 */
@Configuration
@EnableConfigurationProperties(AuthJwtProperties.class)
public class JwtDecoderConfig {

    /**
     * 本系统会签发的三种 {@code aud}。验签阶段只认这个集合。
     *
     * <p>它们是「用途」不是「租户」——本产品每客户独立实例，凭据里没有多租户维度。</p>
     */
    static final Set<String> KNOWN_AUDIENCES = Set.of("scds-edge", "scds-device", "scds-refresh");

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfig.class);

    @Bean
    JwtDecoder jwtDecoder(AuthJwtProperties props) {
        NimbusJwtDecoder decoder = buildDecoder(props);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // 含时间窗（exp / nbf，带默认时钟偏移）与 iss 校验
                JwtValidators.createDefaultWithIssuer(props.issuer()),
                audienceValidator()));
        return decoder;
    }

    private NimbusJwtDecoder buildDecoder(AuthJwtProperties props) {
        String jwksUri = props.jwksUri();
        if (jwksUri != null && !jwksUri.isBlank()) {
            // 该分支下 Nimbus 的 RemoteJWKSet 是**首次验签时**才去拉取，不是启动时；
            // 因此配了 jwks-uri 也不影响冷启动，但断网期间会验签失败——这是接入云管
            // 签发的必然代价，PRIVATE 单机请保持此项为空。
            log.info("JWT 验签公钥来源：JWKS 端点 {}", jwksUri);
            return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        }

        RSAPublicKey publicKey = PemKeys.readPublicKey(props);
        log.info("JWT 验签公钥来源：本地 PEM（SHA-256 指纹 {}），进程不会为此联网",
                PemKeys.fingerprint(publicKey));
        return decoderFor(publicKey);
    }

    /**
     * 用单把本地公钥构造验签器。
     *
     * <p>走 {@link NimbusJwtDecoder#withPublicKey} 而非手搭 {@code JWKSource}：
     * {@code NimbusJwtDecoder} 只公开了接收 {@code JWTProcessor} 与静态工厂两类构造，
     * 没有接收 {@code JWKSource} 的公开构造，手搭就要自己拼 {@code DefaultJWTProcessor}
     * 与 key selector。单把公钥下前者足够，且少了可出错的中间层。</p>
     *
     * <p>代价写清楚：本地 PEM 只支持一把公钥，即**换钥要重启**。这在 PRIVATE 单机是可以
     * 接受的运维动作（换 PEM → 重启 → 用新钥重签），需要「新旧公钥并存、平滑轮换」时
     * 才引入 {@code jwks-uri}（那条路径按 {@code kid} 天然支持并存）。</p>
     */
    private static NimbusJwtDecoder decoderFor(RSAPublicKey publicKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(List.of(toJwk(publicKey))));
        JWSVerificationKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource);
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        return new NimbusJwtDecoder(processor);
    }

    /** 不设 {@code kid}：本进程既是签发者又是验签者，单把密钥下 kid 只增加一处一致性负担。 */
    private static JWK toJwk(RSAPublicKey publicKey) {
        return new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey).build();
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator() {
        return token -> {
            List<String> audience = token.getAudience();
            boolean known = audience != null && audience.stream().anyMatch(KNOWN_AUDIENCES::contains);
            if (known) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    ApiErrorCode.AUTH_TOKEN_INVALID.name(),
                    "令牌 aud 不是本实例认可的用途，实际为 " + audience, null));
        };
    }
}
