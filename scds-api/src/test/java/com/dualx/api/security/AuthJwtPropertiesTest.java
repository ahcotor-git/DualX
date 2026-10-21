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
                    Throwable failure = context.getStartupFailure();
                    while (failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("Local deployment requires PEM keys; remote JWKS is not supported.");
                });
    }

    private static AuthJwtProperties properties(String jwksUri) {
        return new AuthJwtProperties("scds-local-test", null,
                "", "", "", "", jwksUri, null, null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthJwtProperties.class)
    static class PropertiesOnly {
    }
}
