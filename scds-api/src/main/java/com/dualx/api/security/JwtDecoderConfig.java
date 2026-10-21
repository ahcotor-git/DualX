package com.dualx.api.security;

import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.domain.Role;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.math.BigDecimal;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * 因此这里对 {@code aud} 校验「单一已知用途」，不限定具体某一种——
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
    private static final Set<String> KNOWN_ROLES = Arrays.stream(Role.values())
            .map(Role::name).collect(Collectors.toUnmodifiableSet());
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final BigDecimal MIN_NUMERIC_DATE = BigDecimal.valueOf(Long.MIN_VALUE)
            .divide(BigDecimal.valueOf(1000));
    private static final BigDecimal MAX_NUMERIC_DATE = BigDecimal.valueOf(Long.MAX_VALUE)
            .divide(BigDecimal.valueOf(1000));

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfig.class);

    @Bean
    JwtDecoder jwtDecoder(AuthJwtProperties props) {
        return jwtDecoder(props, Clock.systemUTC());
    }

    JwtDecoder jwtDecoder(AuthJwtProperties props, Clock clock) {
        NimbusJwtDecoder decoder = buildDecoder(props);
        decoder.setJwtValidator(tokenValidator(props.issuer(), clock));
        return encoded -> {
            // 这里只拒绝不合格结构，不信任未验签载荷，也不在验签前判定“过期”。
            validateRawClaims(encoded);
            return decoder.decode(encoded);
        };
    }

    private NimbusJwtDecoder buildDecoder(AuthJwtProperties props) {
        String jwksUri = props.jwksUri();
        if (jwksUri != null && !jwksUri.isBlank()) {
            // 该分支下 Nimbus 的 RemoteJWKSet 是**首次验签时**才去拉取，不是启动时；
            // 因此配了 jwks-uri 也不影响冷启动，但断网期间会验签失败——这是接入云管
            // 签发的必然代价，PRIVATE 单机请保持此项为空。
            log.info("JWT 验签公钥来源：JWKS 端点 {}", jwksUri);
            return NimbusJwtDecoder.withJwkSetUri(jwksUri)
                    .jwtProcessorCustomizer(processor ->
                            processor.setJWTClaimsSetVerifier((claims, context) -> { }))
                    .build();
        }

        RSAPublicKey publicKey = PemKeys.readPublicKey(props);
        log.info("JWT 验签公钥来源：本地 PEM（SHA-256 指纹 {}），进程不会为此联网",
                PemKeys.fingerprint(publicKey));
        return decoderFor(publicKey);
    }

    /**
     * 用单把本地公钥构造验签器。
     *
     * <p>本地与JWKS均由Nimbus验证签名，时间检查统一交给Spring侧validator。
     * 关闭Nimbus默认claims verifier，防止它提前将过期抛成BadJWTException；
     * 这不会关闭JWS验签，最终返回的decoder仍强制执行字段、issuer及时间检查。</p>
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
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        return new NimbusJwtDecoder(processor);
    }

    /** 不设 {@code kid}：本进程既是签发者又是验签者，单把密钥下 kid 只增加一处一致性负担。 */
    private static JWK toJwk(RSAPublicKey publicKey) {
        return new com.nimbusds.jose.jwk.RSAKey.Builder(publicKey).build();
    }

    private static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, Clock clock) {
        JwtIssuerValidator identity = new JwtIssuerValidator(issuer);
        return token -> {
            if (identity.validate(token).hasErrors()) {
                return failure(ApiErrorCode.AUTH_TOKEN_INVALID, "JWT issuer validation failed");
            }
            Instant now = clock.instant();
            JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
            // 每次验签使用同一时刻；不修改共享validator的Clock，避免并发竞态。
            timestamps.setClock(Clock.fixed(now, ZoneOffset.UTC));
            OAuth2TokenValidatorResult time = timestamps.validate(token);
            OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithValidators(
                    List.<OAuth2TokenValidator<Jwt>>of(timestamps));
            OAuth2TokenValidatorResult result = standard.validate(token);
            if (!result.hasErrors()) {
                return result;
            }
            // 公共工厂保留证书绑定等默认检查；额外错误说明并非单纯时间失效。
            boolean onlyTimeErrors = result.getErrors().size() == time.getErrors().size();
            Instant notBefore = token.getNotBefore();
            boolean future = notBefore != null && now.plus(CLOCK_SKEW).isBefore(notBefore);
            boolean onlyExpired = onlyTimeErrors && !future
                    && now.minus(CLOCK_SKEW).isAfter(token.getExpiresAt());
            // SEC-02A暂保留旧出口文案兼容，分类本身不依赖默认错误文案。
            return onlyExpired
                    ? failure(ApiErrorCode.AUTH_TOKEN_EXPIRED, "Jwt expired at " + token.getExpiresAt())
                    : failure(ApiErrorCode.AUTH_TOKEN_INVALID, "JWT validation failed");
        };
    }

    private static OAuth2TokenValidatorResult failure(ApiErrorCode code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code.name(), description, null));
    }

    private static void validateRawClaims(String encoded) {
        Map<String, Object> claims;
        try {
            claims = SignedJWT.parse(encoded).getPayload().toJSONObject();
        } catch (ParseException | IllegalArgumentException failure) {
            throw new BadJwtException("Malformed JWT structure");
        }
        if (claims == null) {
            throw new BadJwtException("JWT payload must be a JSON object");
        }
        requireString(claims, "iss");
        String subject = requireString(claims, "sub");
        String audience = singleAudience(claims.get("aud"));
        if ("scds-device".equals(audience)) {
            if (!subject.matches("[1-9][0-9]{0,4}") || Integer.parseInt(subject) > 65532) {
                throw invalidClaim("sub");
            }
            requireUuid(requireString(claims, "sid"), "sid");
        } else {
            requireUuid(subject, "sub");
        }
        requireNumericDate(claims.get("exp"), "exp");
        for (String claim : List.of("iat", "nbf")) {
            if (claims.containsKey(claim)) {
                requireNumericDate(claims.get(claim), claim);
            }
        }
        if (claims.containsKey("jti")) {
            requireString(claims, "jti");
        }
        if (claims.containsKey("roles")) {
            Object roles = claims.get("roles");
            if (!(roles instanceof List<?> list)
                    || list.stream().anyMatch(role -> !(role instanceof String) || !KNOWN_ROLES.contains(role))) {
                throw invalidClaim("roles");
            }
        }
    }

    private static String singleAudience(Object value) {
        Object audience = value;
        if (value instanceof List<?> list) {
            if (list.size() != 1) {
                throw invalidClaim("aud");
            }
            audience = list.getFirst();
        }
        if (!(audience instanceof String text) || !KNOWN_AUDIENCES.contains(text)) {
            throw invalidClaim("aud");
        }
        return text;
    }

    private static String requireString(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidClaim(name);
        }
        return text;
    }

    private static void requireUuid(String value, String name) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalidClaim(name);
            }
        } catch (IllegalArgumentException failure) {
            throw invalidClaim(name);
        }
    }

    private static void requireNumericDate(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw invalidClaim(name);
        }
        try {
            BigDecimal seconds = new BigDecimal(number.toString());
            if (seconds.compareTo(MIN_NUMERIC_DATE) < 0 || seconds.compareTo(MAX_NUMERIC_DATE) > 0) {
                throw invalidClaim(name);
            }
        } catch (NumberFormatException failure) {
            throw invalidClaim(name);
        }
    }

    private static BadJwtException invalidClaim(String name) {
        return new BadJwtException("Missing or invalid JWT claim: " + name);
    }
}
