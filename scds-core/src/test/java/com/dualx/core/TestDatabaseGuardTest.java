package com.dualx.core;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** Negative cases stop at the factory post-processor, before a database bean can connect. */
class TestDatabaseGuardTest {
    private static final String URL =
            "jdbc:mysql://127.0.0.1:13306/scds_next_test?rewriteBatchedStatements=true";

    @TestFactory
    Stream<DynamicTest> rejectsUnsafeTargetsBeforeAnyBeanInitialization() {
        List<DynamicTest> cases = new ArrayList<>();
        for (String url : List.of(
                "jdbc:mysql://127.0.0.1:13306/scds_next?rewriteBatchedStatements=true",
                "jdbc:mysql://production.example:13306/scds_next_test?rewriteBatchedStatements=true",
                "jdbc:mysql://127.0.0.1:3306/scds_next_test?rewriteBatchedStatements=true",
                "jdbc:mysql://127.0.0.1:13306/scds_next_test",
                URL + "&user=root",
                "jdbc:mysql:loadbalance://127.0.0.1:13306/scds_next_test?rewriteBatchedStatements=true",
                "")) {
            cases.add(rejected("unsafe target " + cases.size(), "spring.datasource.url", url));
        }
        for (String user : List.of("root", "scds_app", "")) {
            cases.add(rejected("unsafe account " + user, "spring.datasource.username", user));
        }
        for (String password : List.of("", " ", "${MISSING_TEST_SECRET}")) {
            cases.add(rejected("missing password " + cases.size(), "spring.datasource.password", password));
        }
        for (String property : List.of("spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.flyway.default-schema", "spring.flyway.schemas",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
                "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.jndi-name")) {
            cases.add(rejected("alternate connection " + property, property, "forbidden"));
        }
        cases.add(rejected("clean enabled", "spring.flyway.clean-disabled", "false"));
        cases.add(rejected("out of order enabled", "spring.flyway.out-of-order", "true"));
        return cases.stream();
    }

    @Test
    void approvedLoopbackUrlPasses() {
        assertThatCode(() -> TestCoreApplication.verifyTestDatabase(validEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void approvedLocalhostUrlPasses() {
        var environment = validEnvironment().withProperty("spring.datasource.url", URL.replace("127.0.0.1", "localhost"));
        assertThatCode(() -> TestCoreApplication.verifyTestDatabase(environment)).doesNotThrowAnyException();
    }

    @Test
    void missingPasswordCannotFallbackToDevelopmentCredential() {
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "scds_test")
                .withProperty("SCDS_MYSQL_PASSWORD", "development-secret");
        assertThatThrownBy(() -> TestCoreApplication.verifyTestDatabase(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEST-01")
                .hasMessageNotContaining("development-secret");
    }

    @Test
    void invalidTargetMessageDoesNotEchoPasswordOrUrl() {
        var environment = validEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://private-host/unsafe?password=secret")
                .withProperty("spring.datasource.password", "sensitive-password");
        assertThatThrownBy(() -> TestCoreApplication.verifyTestDatabase(environment))
                .hasMessageNotContaining("private-host")
                .hasMessageNotContaining("sensitive-password");
    }

    private DynamicTest rejected(String name, String property, String value) {
        return dynamicTest(name, () -> {
            var initialized = new AtomicInteger();
            new ApplicationContextRunner()
                    .withUserConfiguration(TestCoreApplication.class, Tripwire.class)
                    .withBean(AtomicInteger.class, () -> initialized)
                    .withPropertyValues("spring.datasource.url=" + URL,
                            "spring.datasource.username=scds_test", "spring.datasource.password=test-only")
                    .withPropertyValues(property + "=" + value)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        Throwable failure = context.getStartupFailure();
                        while (failure.getCause() != null) {
                            failure = failure.getCause();
                        }
                        assertThat(failure).isInstanceOf(IllegalStateException.class)
                                .hasMessage(failureMessage(property));
                        assertThat(initialized).hasValue(0);
                    });
        });
    }

    private static String failureMessage(String property) {
        if (property.equals("spring.flyway.clean-disabled") || property.equals("spring.flyway.out-of-order")) {
            return "TEST-01 requires Flyway clean disabled and ordered migrations.";
        }
        if (property.startsWith("spring.flyway.") || property.startsWith("spring.datasource.hikari.")
                || property.equals("spring.datasource.jndi-name")) {
            return "TEST-01 forbids alternate database connection settings.";
        }
        return "TEST-01 requires the local isolated test database and account; "
                + "development/production targets and missing test credentials are forbidden.";
    }

    private static MockEnvironment validEnvironment() {
        return new MockEnvironment().withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "scds_test")
                .withProperty("spring.datasource.password", "test-only");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Tripwire {
        @Bean
        Object mustNotInitialize(AtomicInteger initialized) {
            initialized.incrementAndGet();
            return new Object();
        }
    }
}

/** The positive path uses the migrated MySQL test database and its real limited account. */
@SpringBootTest
class TestDatabaseIsolationIT {
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired Environment environment;

    @Test
    void effectiveConnectionUsesOnlyTestDatabaseAndAccount() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("scds_next_test");
            assertThat(connection.getMetaData().getUserName()).startsWith("scds_test@");
            assertThat(connection.getMetaData().getURL()).contains("rewriteBatchedStatements=true");
        }
        assertThat(jdbc.sql("SELECT CURRENT_USER()").query(String.class).single()).isEqualTo("scds_test@%");
    }

    @Test
    void grantsAreExactlyScopedWithoutDatabaseNameWildcards() {
        assertThat(jdbc.sql("SHOW GRANTS").query(String.class).list()).containsExactlyInAnyOrder(
                "GRANT USAGE ON *.* TO `scds_test`@`%`",
                "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES, INDEX, ALTER "
                        + "ON `scds\\_next\\_test`.* TO `scds_test`@`%`");
    }

    @Test
    void developmentDatabaseReadsAreDenied() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT COUNT(*) FROM scds_next.t_account"))
                    .isInstanceOfSatisfying(SQLException.class, error ->
                            assertThat(error.getSQLState()).isEqualTo("42000"));
        }
    }

    @Test
    void developmentDatabaseWritesAreDeniedEvenWithEmptyPredicate() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // This has no rows to delete even if the grant regresses; permission must still be denied.
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM scds_next.t_account WHERE 1=0"))
                    .isInstanceOfSatisfying(SQLException.class, error ->
                            assertThat(error.getSQLState()).isEqualTo("42000"));
        }
    }

    @Test
    void allExistingMigrationsAppliedWithoutChangingHistory() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("1", "2", "3", "4", "5", "6", "7", "9", "10");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")
                .query(Integer.class).single()).isEqualTo(19);
    }

    @Test
    void connectionSessionIsUtc() {
        assertThat(jdbc.sql("SELECT @@session.time_zone").query(String.class).single()).isEqualTo("+00:00");
    }

    @Test
    void dedicatedConfigurationHasNoDevelopmentImportOrUnsafeFlywayMode() {
        TestCoreApplication.verifyTestDatabase(environment);
        assertThat(environment.getProperty("spring.config.import[0]")).contains(".env.test");
        assertThat(environment.getProperty("spring.config.import[1]")).contains(".env.test");
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.out-of-order", Boolean.class)).isFalse();
    }
}
