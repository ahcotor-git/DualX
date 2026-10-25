package com.dualx.core;

import com.dualx.core.port.TokenSigner;
import com.dualx.core.state.SituationSessionBinder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Set;

import static org.mockito.Mockito.mock;

/**
 * 仅测试期使用的启动类。
 *
 * <p>scds-core 是可复用库、本身没有 main，但仓储层的集成测试需要一套 Spring 上下文
 * 来装配 DataSource / JdbcClient / Flyway。放在 src/test 下，不会被打进产物。
 */
@SpringBootApplication
class TestCoreApplication {

    /** Run before DataSource/Flyway or any other non-infrastructure bean can be instantiated. */
    @Bean
    static BeanFactoryPostProcessor testDatabaseGuard(Environment environment) {
        return ignored -> verifyTestDatabase(environment);
    }

    static void verifyTestDatabase(Environment environment) {
        Set<String> allowedUrls = Set.of(
                "jdbc:mysql://127.0.0.1:13306/scds_next_test?rewriteBatchedStatements=true",
                "jdbc:mysql://localhost:13306/scds_next_test?rewriteBatchedStatements=true");
        if (!allowedUrls.contains(environment.getProperty("spring.datasource.url", ""))
                || !"scds_test".equals(environment.getProperty("spring.datasource.username", ""))
                || !hasPassword(environment)) {
            throw new IllegalStateException("TEST-01 requires the local isolated test database and account; "
                    + "development/production targets and missing test credentials are forbidden.");
        }
        // Flyway supports a separate connection; forbid bypassing the guarded datasource.
        for (String property : Set.of("spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.flyway.default-schema", "spring.flyway.schemas",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
                "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.jndi-name")) {
            if (environment.containsProperty(property)) {
                throw new IllegalStateException("TEST-01 forbids alternate database connection settings.");
            }
        }
        if (!environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true)
                || environment.getProperty("spring.flyway.out-of-order", Boolean.class, false)) {
            throw new IllegalStateException("TEST-01 requires Flyway clean disabled and ordered migrations.");
        }
    }

    private static boolean hasPassword(Environment environment) {
        try {
            String password = environment.getProperty("spring.datasource.password");
            return password != null && !password.isBlank() && !password.contains("${");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return false;
        }
    }

    /** 核心集成测试只替换签发适配器，业务服务与数据库仍使用真实实现。 */
    @Bean
    TokenSigner tokenSigner() {
        return mock(TokenSigner.class);
    }

    /**
     * 态势会话编排的装配点在 {@code scds-app} 的 {@code SituationStateConfig}（它还要装配
     * {@link com.dualx.core.state.SituationStateService} 及其一串协作者），本测试上下文里没有。
     *
     * <p>{@code ExerciseLifecycleService} 对它有**强依赖**——生产侧缺装配就该起不来，故不放宽
     * 成可选注入；这里只补一个桩，让生命周期服务的集成测试能验证「库与内存态的启动顺序」。
     * 真正的编成装载由 {@code scds-app} 的 {@code SituationStateIT} 用真 binder 覆盖。
     */
    @Bean
    SituationSessionBinder situationSessionBinder() {
        return mock(SituationSessionBinder.class);
    }
}
