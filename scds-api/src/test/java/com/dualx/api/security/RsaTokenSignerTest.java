package com.dualx.api.security;

import com.dualx.core.domain.Role;
import com.dualx.core.port.AccessTokenClaims;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.RefreshTokenClaims;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌签发器的签-验往返测试。
 *
 * <p>关键点在于**验签用的是生产装配的那条路径**（直接调 {@link JwtDecoderConfig#jwtDecoder}），
 * 而不是测试里另搭一套 nimbus 解码。自己签的令牌自己验不过，最常见的原因就是签发侧与
 * 验签侧对 {@code kid}、{@code alg}、{@code aud} 的处理不一致——另搭一套解码器恰好会
 * 把这类不一致掩盖掉。</p>
 *
 * <p>密钥对在测试内现生成，不依赖 {@code dev-keys/}：本类验证的是编解码逻辑，与具体
 * 密钥材料无关，用固定密钥反而会把「密钥换掉后是否仍自洽」这一层测丢。</p>
 */
class RsaTokenSignerTest {

    private static final String ISSUER = "scds-edge-test";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final Duration DEVICE_TTL = Duration.ofHours(12);

    private static RSAPrivateKey privateKey;
    private static AuthJwtProperties signingProperties;
    private static RsaTokenSigner signer;
    private static JwtDecoder decoder;

    @BeforeAll
    static void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();

        signingProperties = new AuthJwtProperties(ISSUER, true,
                pem("PUBLIC KEY", publicKey.getEncoded()), null,
                pem("PRIVATE KEY", privateKey.getEncoded()), null,
                null, null, ACCESS_TTL, REFRESH_TTL, DEVICE_TTL);

        signer = new RsaTokenSigner(signingProperties);
        decoder = new JwtDecoderConfig().jwtDecoder(signingProperties);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    @DisplayName("accessToken：sub/aud/roles/jti 齐全，且能被生产解码器验签")
    void accessTokenRoundTripsThroughProductionDecoder() {
        UUID accountId = UUID.randomUUID();

        Jwt jwt = decoder.decode(signer.signAccessToken(
                new AccessTokenClaims(accountId, List.of(Role.DIRECTOR, Role.VIEWER))));

        assertThat(jwt.getSubject()).isEqualTo(accountId.toString());
        assertThat(jwt.getAudience()).containsExactly("scds-edge");
        // 刻意用 getClaimAsString("iss") 而非 getIssuer()：后者会把 iss 当 URL 解析，
        // 而 iss 在本系统是「部署时确定的实例标识」（默认 scds-edge），不是 URL。
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("DIRECTOR", "VIEWER");
        // jti 只为日志串联而存在，但不能没有——否则排查时无法把两次请求串起来。
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    @DisplayName("三种令牌的 aud 互不相同——这是唯一的横向越权防线")
    void eachTokenKindCarriesItsOwnAudience() {
        assertThat(decoder.decode(signer.signAccessToken(new AccessTokenClaims(UUID.randomUUID(), List.of(Role.VIEWER))))
                .getAudience()).containsExactly("scds-edge");
        assertThat(decoder.decode(signer.signRefreshToken(new RefreshTokenClaims(UUID.randomUUID(), UUID.randomUUID())))
                .getAudience()).containsExactly("scds-refresh");
        assertThat(decoder.decode(signer.signDeviceToken(new DeviceTokenClaims(7, UUID.randomUUID())))
                .getAudience()).containsExactly("scds-device");
    }

    @Test
    @DisplayName("refreshToken 的 jti 用调用方给的 tokenId：它必须等于 t_refresh_token.id")
    void refreshTokenUsesCallerSuppliedTokenId() {
        UUID tokenId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Jwt jwt = decoder.decode(signer.signRefreshToken(new RefreshTokenClaims(tokenId, accountId)));

        assertThat(jwt.getId()).isEqualTo(tokenId.toString());
        assertThat(jwt.getSubject()).isEqualTo(accountId.toString());
    }

    @Test
    @DisplayName("设备令牌带 sid 与马甲号：单端在线比对的就是这两个值")
    void deviceTokenCarriesSidAndVestNum() {
        UUID sessionId = UUID.randomUUID();

        Jwt jwt = decoder.decode(signer.signDeviceToken(new DeviceTokenClaims(42, sessionId)));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
    }

    @Test
    @DisplayName("有效期取自配置，不在实现里另写一份常量")
    void expiryComesFromConfiguration() {
        Jwt access = decoder.decode(signer.signAccessToken(new AccessTokenClaims(UUID.randomUUID(), List.of(Role.VIEWER))));
        Jwt refresh = decoder.decode(signer.signRefreshToken(new RefreshTokenClaims(UUID.randomUUID(), UUID.randomUUID())));
        Jwt device = decoder.decode(signer.signDeviceToken(new DeviceTokenClaims(1, UUID.randomUUID())));

        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(ACCESS_TTL);
        assertThat(Duration.between(refresh.getIssuedAt(), refresh.getExpiresAt())).isEqualTo(REFRESH_TTL);
        assertThat(Duration.between(device.getIssuedAt(), device.getExpiresAt())).isEqualTo(DEVICE_TTL);
        assertThat(signer.accessTokenTtl()).isEqualTo(ACCESS_TTL);
        assertThat(signer.refreshTokenTtl()).isEqualTo(REFRESH_TTL);
        assertThat(signer.deviceTokenTtl()).isEqualTo(DEVICE_TTL);
    }

    @Test
    @DisplayName("signing-enabled=false：装配成功、enabled() 为假，且拒绝签发")
    void disabledSignerRefusesToSignWithoutNeedingAPrivateKey() {
        // 关键：这份配置**没有私钥**，构造仍然成功——只验签的实例本来就没有私钥。
        RsaTokenSigner disabled = new RsaTokenSigner(new AuthJwtProperties(
                ISSUER, false, null, null, null, null, null, null, null, null, null));

        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.signAccessToken(new AccessTokenClaims(UUID.randomUUID(), List.of(Role.VIEWER))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-enabled=false");
    }

    @Test
    @DisplayName("signing-enabled=true 却缺私钥：装配期就响亮失败，而不是等第一次登录才炸")
    void missingPrivateKeyWhileSigningEnabledFailsAtStartup() {
        AuthJwtProperties misconfigured = new AuthJwtProperties(
                ISSUER, true, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new RsaTokenSigner(misconfigured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SCDS_JWT_PRIVATE_KEY");
    }
}
