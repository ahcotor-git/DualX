package com.dualx.core.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IdentitySyncProperties} 绑定与「已配置」判据（设计 03c §7.2）。
 *
 * <p>覆盖：给出路径则绑定生效；整段缺席为 {@code null}；仅空白串与缺席同义（视为未配置）。</p>
 */
class IdentitySyncPropertiesTest {

    @Test
    void bindsConfiguredImportFile() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("scds.identity.import-file=/var/lib/scds/accounts.json")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdentitySyncProperties.class).importFile())
                            .isEqualTo("/var/lib/scds/accounts.json");
                });
    }

    @Test
    void absentPropertyBindsNull() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdentitySyncProperties.class).importFile()).isNull();
                });
    }

    @Test
    void blankPropertyIsTreatedAsAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("scds.identity.import-file=  ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdentitySyncProperties.class).importFile()).isNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentitySyncProperties.class)
    static class PropertiesOnly {
    }
}
