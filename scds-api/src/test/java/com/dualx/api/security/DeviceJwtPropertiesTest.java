package com.dualx.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeviceJwtProperties} 绑定与负向校验（设计 03b §10.1 工作包 2b）。
 *
 * <p>覆盖：整段缺席时默认关闭且 TTL 为 12h；显式启用而缺 issuer / 缺公钥 / 缺私钥 → 抛；
 * TTL 非正 → 抛；键前缀确为 {@code scds.auth.jwt.device}。</p>
 *
 * <p>「公私钥不配对 / RSA 强度不足」与「与账号 issuer / 公钥相同」两项<b>不在本测试范围</b>：
 * 二者在 03b §10.2 工作包 3a 的 {@code DeviceRsaTokenSigner} 装配期校验（需 {@code PemKeys}
 * 参数化并同时看到账号配置），本包 {@link DeviceJwtProperties} 不读取密钥材料。</p>
 */
class DeviceJwtPropertiesTest {

    @Test
    void absentDeviceSectionBindsDisabledWithDefaultTtl() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DeviceJwtProperties properties = context.getBean(DeviceJwtProperties.class);
                    assertThat(properties.signingEnabled()).isFalse();
                    assertThat(properties.deviceTokenTtl()).isEqualTo(Duration.ofHours(12));
                    assertThat(properties.issuer()).isNull();
                });
    }

    @Test
    void explicitDevicePrefixBindsWhenSigningEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "scds.auth.jwt.device.signing-enabled=true",
                        "scds.auth.jwt.device.issuer=edge-device-authority",
                        "scds.auth.jwt.device.public-key=-----BEGIN PUBLIC KEY-----",
                        "scds.auth.jwt.device.private-key-file=/protected/device-private.pem",
                        "scds.auth.jwt.device.device-token-ttl=6h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DeviceJwtProperties properties = context.getBean(DeviceJwtProperties.class);
                    assertThat(properties.signingEnabled()).isTrue();
                    assertThat(properties.issuer()).isEqualTo("edge-device-authority");
                    assertThat(properties.deviceTokenTtl()).isEqualTo(Duration.ofHours(6));
                });
    }

    @Test
    void signingEnabledWithoutIssuerFailsDuringBinding() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "scds.auth.jwt.device.signing-enabled=true",
                        "scds.auth.jwt.device.public-key=-----BEGIN PUBLIC KEY-----",
                        "scds.auth.jwt.device.private-key=-----BEGIN PRIVATE KEY-----")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("scds.auth.jwt.device.issuer");
                });
    }

    @Test
    void signingEnabledWithoutPublicKeyFails() {
        assertThatThrownBy(() -> device(true, "device-iss", null, "priv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scds.auth.jwt.device.public-key");
    }

    @Test
    void signingEnabledWithoutPrivateKeyFails() {
        assertThatThrownBy(() -> device(true, "device-iss", "pub", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scds.auth.jwt.device.private-key");
    }

    @Test
    void signingEnabledWithBothKeySourcesBinds() {
        DeviceJwtProperties properties = device(true, "device-iss", "pub", "priv");
        assertThat(properties.signingEnabled()).isTrue();
        assertThat(properties.issuer()).isEqualTo("device-iss");
        assertThat(properties.deviceTokenTtl()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void disabledInstanceAllowsAbsentKeys() {
        // 只验签不签发的实例，或整段缺席的默认形态：不要求任何密钥。
        DeviceJwtProperties properties = device(false, null, null, null);
        assertThat(properties.signingEnabled()).isFalse();
        assertThat(properties.deviceTokenTtl()).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void explicitNonPositiveTtlFailsInsteadOfFallingBackToDefault() {
        assertThatThrownBy(() -> new DeviceJwtProperties(null, false, null, null, null, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device-token-ttl");
        assertThatThrownBy(() -> new DeviceJwtProperties(null, false, null, null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device-token-ttl");
    }

    private static DeviceJwtProperties device(Boolean signingEnabled, String issuer, String publicKey, String privateKey) {
        return new DeviceJwtProperties(issuer, signingEnabled, publicKey, null, privateKey, null, null);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeviceJwtProperties.class)
    static class PropertiesOnly {
    }
}
