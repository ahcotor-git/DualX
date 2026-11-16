package com.dualx.api.security;

import com.dualx.core.port.DeviceTokenClaims;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备令牌签发器的签-验往返与装配期失败关闭测试。
 *
 * <p>验签仍走**生产装配的那条路径**（{@link JwtDecoderConfig#jwtDecoder}），只是把 issuer / 公钥换成设备
 * 那一套——本阶段尚无「按 aud 分派双用途 decoder」的门面（工作包 4），因此这里用设备密钥/身份构造一个
 * 账号语境的可分配对象来复用生产解码器，而不是在测试里另搭一套 nimbus。自己签的令牌自己验不过，
 * 最常见的原因就是签发侧与验签侧对 {@code kid}、{@code alg}、{@code aud} 的处理不一致，另搭解码器恰好
 * 会把这类不一致掩盖掉。</p>
 *
 * <p>密钥对在测试内现生成：本类验证的是编解码与装配校验逻辑，与具体密钥材料无关。设备与账号故意用
 * **两对不同的密钥、两个不同的 issuer**，这正是 §3「两用途 issuer 与公钥必须不同」要守的不变量。</p>
 */
class DeviceRsaTokenSignerTest {

    private static final String DEVICE_ISSUER = "scds-edge-device-test";
    private static final String ACCOUNT_ISSUER = "scds-edge-account-test";

    /** 故意区别于账号的默认设备 TTL（12h），以证明有效期取自**设备**配置。 */
    private static final Duration DEVICE_TTL = Duration.ofHours(6);
    private static final Duration ACCOUNT_ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration ACCOUNT_REFRESH_TTL = Duration.ofDays(14);
    private static final Duration ACCOUNT_DEVICE_TTL = Duration.ofHours(12);

    private static RSAPrivateKey devicePrivate;
    private static RSAPublicKey devicePublic;
    private static RSAPrivateKey accountPrivate;
    private static RSAPublicKey accountPublic;
    private static AuthJwtProperties accountProperties;
    private static DeviceRsaTokenSigner signer;
    private static JwtDecoder deviceDecoder;

    @BeforeAll
    static void setUp() throws NoSuchAlgorithmException {
        KeyPair deviceKeys = keyPair(2048);
        KeyPair accountKeys = keyPair(2048);
        devicePrivate = (RSAPrivateKey) deviceKeys.getPrivate();
        devicePublic = (RSAPublicKey) deviceKeys.getPublic();
        accountPrivate = (RSAPrivateKey) accountKeys.getPrivate();
        accountPublic = (RSAPublicKey) accountKeys.getPublic();

        accountProperties = new AuthJwtProperties(ACCOUNT_ISSUER, true,
                pem("PUBLIC KEY", accountPublic.getEncoded()), null,
                pem("PRIVATE KEY", accountPrivate.getEncoded()), null,
                null, null, ACCOUNT_ACCESS_TTL, ACCOUNT_REFRESH_TTL, ACCOUNT_DEVICE_TTL);

        signer = new DeviceRsaTokenSigner(
                deviceProperties(DEVICE_ISSUER, devicePublic, devicePrivate, DEVICE_TTL),
                accountProperties);

        // 让生产解码器按「设备 issuer + 设备公钥」构造：等价于包 4 落地后按 aud 分派到设备 decoder 的效果。
        deviceDecoder = new JwtDecoderConfig().jwtDecoder(new AuthJwtProperties(
                DEVICE_ISSUER, true, pem("PUBLIC KEY", devicePublic.getEncoded()), null,
                pem("PRIVATE KEY", devicePrivate.getEncoded()), null,
                null, null, ACCOUNT_ACCESS_TTL, ACCOUNT_REFRESH_TTL, DEVICE_TTL));
    }

    /** 构造一份「启用设备签发」的配置；键为 {@code null} 时表示该项缺席（由各自负例决定是否合法）。 */
    private static DeviceJwtProperties deviceProperties(String issuer, RSAPublicKey publicKey,
                                                        RSAPrivateKey privateKey, Duration ttl) {
        return new DeviceJwtProperties(issuer, true,
                publicKey == null ? null : pem("PUBLIC KEY", publicKey.getEncoded()), null,
                privateKey == null ? null : pem("PRIVATE KEY", privateKey.getEncoded()), null,
                ttl);
    }

    private static KeyPair keyPair(int bits) throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    @DisplayName("设备令牌：aud=scds-device、sub=马甲号、sid=会话 ID，且能被生产解码器验签")
    void deviceTokenCarriesAudSubAndSid() {
        UUID sessionId = UUID.randomUUID();

        Jwt jwt = deviceDecoder.decode(signer.signDeviceToken(new DeviceTokenClaims(42, sessionId)));

        assertThat(jwt.getAudience()).containsExactly("scds-device");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        // 刻意用 getClaimAsString("iss") 而非 getIssuer()：iss 是部署实例标识，不是 URL。
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(DEVICE_ISSUER);
        // jti 只为日志串联而存在，但校验期要求它是字符串，不能缺席。
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    @DisplayName("有效期取自**设备**配置：与账号的设备 TTL 无关")
    void expiryComesFromDeviceConfiguration() {
        Jwt jwt = deviceDecoder.decode(signer.signDeviceToken(new DeviceTokenClaims(1, UUID.randomUUID())));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(DEVICE_TTL);
        assertThat(signer.deviceTokenTtl()).isEqualTo(DEVICE_TTL);
        // 反例护栏：账号配置里的设备 TTL 是 12h；若实现误读了账号配置，这里会立刻失败。
        assertThat(signer.deviceTokenTtl()).isNotEqualTo(ACCOUNT_DEVICE_TTL);
    }

    @Test
    @DisplayName("signing-enabled=false：装配成功、不读设备私钥、enabled() 为假且拒绝签发")
    void disabledSignerRefusesToSignWithoutNeedingAPrivateKey() {
        // 关键：这份配置**没有任何设备密钥**，构造仍然成功——只验签的实例本来就没有设备私钥。
        DeviceJwtProperties disabled = new DeviceJwtProperties(null, false, null, null, null, null, null);
        DeviceRsaTokenSigner off = new DeviceRsaTokenSigner(disabled, accountProperties);

        assertThat(off.enabled()).isFalse();
        // 整段缺席时 TTL 取 DeviceJwtProperties 的默认值 12h（与账号配置无关）。
        assertThat(off.deviceTokenTtl()).isEqualTo(Duration.ofHours(12));
        assertThatThrownBy(() -> off.signDeviceToken(new DeviceTokenClaims(1, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-enabled=false");
    }

    @Test
    @DisplayName("设备公私钥不配对：装配期就失败关闭，而不是签出没人验得过的令牌")
    void mismatchedDeviceKeyPairFailsAtStartup() throws NoSuchAlgorithmException {
        RSAPublicKey otherPublic = (RSAPublicKey) keyPair(2048).getPublic();

        assertThatThrownBy(() -> new DeviceRsaTokenSigner(
                deviceProperties(DEVICE_ISSUER, otherPublic, devicePrivate, DEVICE_TTL), accountProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是同一对");
    }

    @Test
    @DisplayName("设备密钥强度不足（RSA < 2048）：装配期失败关闭")
    void weakDeviceKeyFailsAtStartup() throws NoSuchAlgorithmException {
        KeyPair weak = keyPair(1024);

        assertThatThrownBy(() -> new DeviceRsaTokenSigner(
                deviceProperties(DEVICE_ISSUER, (RSAPublicKey) weak.getPublic(),
                        (RSAPrivateKey) weak.getPrivate(), DEVICE_TTL), accountProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("强度");
    }

    @Test
    @DisplayName("设备 issuer 与账号相同：装配期失败关闭（§3 两用途身份必须不同）")
    void deviceIssuerSameAsAccountFailsAtStartup() {
        assertThatThrownBy(() -> new DeviceRsaTokenSigner(
                deviceProperties(ACCOUNT_ISSUER, devicePublic, devicePrivate, DEVICE_TTL), accountProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer 不得与账号 issuer 相同");
    }

    @Test
    @DisplayName("设备公钥与账号相同：装配期失败关闭（§3 不得复用账号密钥）")
    void devicePublicKeySameAsAccountFailsAtStartup() {
        // 设备复用账号密钥对、但 issuer 不同 → 配对/强度都过，只有「公钥与账号相同」这一关能拦住它。
        assertThatThrownBy(() -> new DeviceRsaTokenSigner(
                deviceProperties(DEVICE_ISSUER, accountPublic, accountPrivate, DEVICE_TTL), accountProperties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公钥不得与账号公钥相同");
    }
}
