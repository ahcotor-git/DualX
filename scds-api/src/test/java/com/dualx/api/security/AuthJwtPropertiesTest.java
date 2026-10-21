package com.dualx.api.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthJwtPropertiesTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void localPemConfigurationNormalizesAbsentJwks(String jwksUri) {
        AuthJwtProperties properties = properties(jwksUri);
        assertThat(properties.jwksUri()).isEmpty();
        assertThat(properties.signingEnabled()).isTrue();
        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.deviceTokenTtl()).isEqualTo(Duration.ofHours(12));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://must-not-contact.invalid/jwks",
            "http://127.0.0.1/jwks",
            "file:///local/jwks.json",
            " https://must-not-contact.invalid/jwks "
    })
    void rejectsAlternateKeySourcesWithoutEchoingTheirLocation(String jwksUri) {
        assertThatThrownBy(() -> properties(jwksUri))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Local deployment requires PEM keys; remote JWKS is not supported.")
                .hasMessageNotContaining(jwksUri);
    }

    @Test
    void localDefaultBindsWithoutDatabaseOrNetworkInfrastructure() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AuthJwtProperties.class).jwksUri()).isEmpty();
                    assertThat(context.getBean(AuthJwtProperties.class).signingEnabled()).isTrue();
                });
    }

    @Test
    void externalOverrideFailsDuringBindingBeforeDecoderConstruction() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("scds.auth.jwt.jwks-uri=https://must-not-contact.invalid/jwks")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Local deployment requires PEM keys; remote JWKS is not supported.");
                });
    }

    @Test
    void explicitNonPositiveTtlFailsInsteadOfFallingBackToDefaults() {
        assertThatThrownBy(() -> ttls(Duration.ZERO, Duration.ofDays(14), Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-token-ttl");
        assertThatThrownBy(() -> ttls(Duration.ofMinutes(30), Duration.ofSeconds(-1), Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-token-ttl");
        assertThatThrownBy(() -> ttls(Duration.ofMinutes(30), Duration.ofDays(14), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device-token-ttl");
    }

    @Test
    void refreshTtlMustOutlastAccessTtl() {
        // access 与 refresh 相等也不行：滚动换发靠的是「access 先过期、refresh 还有效」的窗口。
        assertThatThrownBy(() -> ttls(Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-token-ttl")
                .hasMessageContaining("outlast");
        assertThatThrownBy(() -> ttls(Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofHours(12)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh-token-ttl");
    }

    @Test
    void ttlMisconfigurationFailsDuringBindingNotAtFirstLogin() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("scds.auth.jwt.device-token-ttl=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("device-token-ttl");
                });
    }

    private static AuthJwtProperties properties(String jwksUri) {
        return new AuthJwtProperties("scds-local-test", null,
                "", "", "", "", jwksUri, null, null, null, null);
    }

    private static AuthJwtProperties ttls(Duration access, Duration refresh, Duration device) {
        return new AuthJwtProperties("scds-local-test", null,
                "", "", "", "", "", null, access, refresh, device);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthJwtProperties.class)
    static class PropertiesOnly {
    }
}
