package com.dualx;

import com.dualx.api.auth.AccountAuthController;
import com.dualx.api.auth.AuthController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.security.AuthJwtProperties;
import com.dualx.api.security.RsaTokenSigner;
import com.dualx.api.security.SecurityConfig;
import com.dualx.core.auth.AdminAccountBootstrap;
import com.dualx.core.auth.AdminProperties;
import com.dualx.core.auth.AuthService;
import com.dualx.core.domain.Role;
import com.dualx.core.port.AccessTokenClaims;
import com.dualx.core.port.DeviceTokenClaims;
import com.dualx.core.port.RefreshTokenClaims;
import com.dualx.core.port.TokenSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** Complete production entrypoint, real MySQL and real HTTP/security; no business mocks. */
class ApplicationStartupIT {

    @TestFactory
    Stream<DynamicTest> unsafeSettingsFailBeforeAnyBeanInitialization() {
        Map<String, String> unsafe = new LinkedHashMap<>();
        unsafe.put("spring.datasource.url", "jdbc:mysql://127.0.0.1:13306/scds_next");
        unsafe.put("spring.datasource.username", "scds_app");
        unsafe.put("spring.datasource.password", "${missing-test-secret}");
        unsafe.put("server.address", "0.0.0.0");
        unsafe.put("server.port", "11001");
        unsafe.put("spring.profiles.active", "local");
        unsafe.put("spring.config.location", "optional:file:../application.yml");
        unsafe.put("spring.config.additional-location", "optional:file:../");
        unsafe.put("spring.config.import", "optional:file:../.env[.properties]");
        unsafe.put("spring.config.import[0]", "optional:file:../.env[.properties]");
        unsafe.put("spring.profiles.include", "local");
        unsafe.put("spring.profiles.include[0]", "local");
        unsafe.put("spring.profiles.group.full-startup-test[0]", "local");
        unsafe.put("scds.admin.username", "unwanted-admin");
        unsafe.put("scds.admin.password", "unwanted-secret");
        unsafe.put("scds.admin.display-name", "unwanted-admin");
        unsafe.put("scds.auth.jwt.public-key-file", "deployment-public.pem");
        unsafe.put("scds.auth.jwt.private-key-file", "deployment-private.pem");
        unsafe.put("scds.auth.jwt.jwks-uri", "https://must-not-contact.invalid/jwks");
        unsafe.put("spring.flyway.url", "jdbc:mysql://must-not-contact.invalid/scds_next");
        unsafe.put("spring.flyway.user", "root");
        unsafe.put("spring.flyway.password", "unwanted-secret");
        unsafe.put("spring.flyway.default-schema", "scds_next");
        unsafe.put("spring.flyway.schemas", "scds_next");
        unsafe.put("spring.datasource.jndi-name", "jdbc/unwanted");
        unsafe.put("spring.datasource.hikari.jdbc-url", ApplicationStartupSupport.URL);
        unsafe.put("spring.datasource.hikari.username", "root");
        unsafe.put("spring.datasource.hikari.password", "unwanted-secret");
        unsafe.put("spring.datasource.hikari.data-source-class-name", "unwanted.Driver");
        unsafe.put("spring.datasource.hikari.data-source-properties.user", "root");
        unsafe.put("spring.datasource.hikari.data-source-properties.password", "unwanted-secret");
        unsafe.put("spring.datasource.hikari.data-source-properties.url", "unwanted");
        unsafe.put("spring.flyway.clean-disabled", "false");
        unsafe.put("spring.flyway.out-of-order", "true");
        unsafe.put("management.server.port", "11002");

        return unsafe.entrySet().stream().map(entry -> dynamicTest(entry.getKey(), () -> {
            var initialized = new AtomicInteger();
            var environment = safeEnvironment().withProperty(entry.getKey(), entry.getValue());
            try (var context = new GenericApplicationContext()) {
                context.setEnvironment(environment);
                ApplicationStartupSupport.installGuard(context);
                context.registerBean("initializationTripwire", Object.class, () -> {
                    initialized.incrementAndGet();
                    return new Object();
                });
                assertThatThrownBy(context::refresh)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(ApplicationStartupSupport.GUARD_MESSAGE);
                assertThat(initialized).hasValue(0);
            }
        }));
    }

    @ParameterizedTest(name = "full production startup: signing={0}")
    @ValueSource(booleans = {true, false})
    void productionApplicationStartsAndClosesWithRealSecurityAndDatabase(boolean signing) throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        ConfigurableEnvironment environment = ApplicationStartupSupport.environment(signing, key);
        Map<String, Long> before = rowCounts(environment);
        HikariDataSource pool = null;
        int port = -1;
        try (var context = ApplicationStartupSupport.start(environment)) {
            assertThat(context).isInstanceOf(ServletWebServerApplicationContext.class);
            var web = (ServletWebServerApplicationContext) context;
            port = web.getWebServer().getPort();
            assertThat(port).isPositive().isNotIn(11001, 12001, 13306);
            assertThat(context.getEnvironment().getActiveProfiles())
                    .containsExactly(ApplicationStartupSupport.PROFILE);
            assertThat(context.getBeansOfType(ScdsNextApplication.class)).hasSize(1);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
            assertThat(context.getBeansOfType(SecurityConfig.class)).hasSize(1);
            assertThat(context.getBeansOfType(ApiExceptionHandler.class)).hasSize(1);
            assertThat(context.getBeansOfType(AuthController.class)).hasSize(1);
            // 签发端点随 signing-enabled 条件注册：只验签实例不注册 login/refresh/logout（设计 03 Q1）
            assertThat(context.getBeansOfType(AccountAuthController.class)).hasSize(signing ? 1 : 0);
            assertThat(context.getBeansOfType(AdminAccountBootstrap.class)).hasSize(1);
            assertThat(context.getBeansOfType(AuthService.class)).hasSize(1);
            assertThat(context.getBean(AdminProperties.class))
                    .isEqualTo(new AdminProperties("", "", ""));
            assertThat(context.getBean(AuthJwtProperties.class).signingEnabled()).isEqualTo(signing);
            assertThat(context.getBean(AuthJwtProperties.class).privateKey().isEmpty()).isEqualTo(!signing);
            assertThat(context.getBean(TokenSigner.class)).isInstanceOf(RsaTokenSigner.class);
            assertThat(context.getBeansOfType(TokenSigner.class)).hasSize(1);

            pool = context.getBean(HikariDataSource.class);
            JdbcClient jdbc = context.getBean(JdbcClient.class);
            try (var connection = pool.getConnection()) {
                assertThat(connection.getCatalog()).isEqualTo("scds_next_test");
                assertThat(connection.getMetaData().getUserName()).startsWith("scds_test@");
            }
            assertThat(jdbc.sql("SELECT CURRENT_USER()").query(String.class).single()).isEqualTo("scds_test@%");
            assertThat(jdbc.sql("SELECT @@session.time_zone").query(String.class).single()).isEqualTo("+00:00");
            assertThat(jdbc.sql("SHOW GRANTS").query(String.class).list()).containsExactlyInAnyOrder(
                    "GRANT USAGE ON *.* TO `scds_test`@`%`",
                    "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES, INDEX, ALTER "
                            + "ON `scds\\_next\\_test`.* TO `scds_test`@`%`");
            context.getBean(Flyway.class).validate();
            assertThat(jdbc.sql("SELECT version FROM flyway_schema_history "
                    + "WHERE success=1 ORDER BY installed_rank").query(String.class).list())
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "9");

            TokenSigner signer = context.getBean(TokenSigner.class);
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            UUID subject = UUID.randomUUID();
            String admin;
            if (signing) {
                admin = signer.signAccessToken(new AccessTokenClaims(subject, List.of(Role.ADMIN)));
                assertThat(decoder.decode(admin).getSubject()).isEqualTo(subject.toString());
                assertThat(decoder.decode(signer.signRefreshToken(
                        new RefreshTokenClaims(UUID.randomUUID(), subject))).getAudience())
                        .containsExactly("scds-refresh");
                UUID sid = UUID.randomUUID();
                assertThat(decoder.decode(signer.signDeviceToken(new DeviceTokenClaims(7, sid)))
                        .getClaimAsString("sid")).isEqualTo(sid.toString());
            } else {
                assertThat(signer.enabled()).isFalse();
                assertThatThrownBy(() -> signer.signAccessToken(
                        new AccessTokenClaims(subject, List.of(Role.ADMIN))))
                        .isInstanceOf(IllegalStateException.class);
                admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
                assertThat(decoder.decode(admin).getAudience()).containsExactly("scds-edge");
            }

            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                String base = "http://127.0.0.1:" + port;
                var health = get(client, base, "/actuator/health", null);
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");
                expectProblem(mapper, get(client, base, "/api/v1/auth/me", null),
                        401, "AUTH_TOKEN_INVALID", "/api/v1/auth/me");
                String wrong = ApplicationStartupSupport.externalAccessToken(
                        ApplicationStartupSupport.newKeyPair(), List.of("ADMIN"));
                expectProblem(mapper, get(client, base, "/actuator/metrics", wrong),
                        401, "AUTH_TOKEN_INVALID", "/actuator/metrics");
                String viewer = ApplicationStartupSupport.externalAccessToken(key, List.of("VIEWER"));
                expectProblem(mapper, get(client, base, "/actuator/metrics/jvm.memory.used", viewer),
                        403, "AUTH_FORBIDDEN", "/actuator/metrics/jvm.memory.used");
                var metric = get(client, base, "/actuator/metrics/jvm.memory.used", admin);
                assertThat(metric.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(metric.body()).path("name").asText()).isEqualTo("jvm.memory.used");
                // /auth/me 已接核心 AuthService：subject 未入库 → 401，而非占位 501
                expectProblem(mapper, get(client, base, "/api/v1/auth/me", admin),
                        401, "AUTH_TOKEN_INVALID", "/api/v1/auth/me");
                expectProblem(mapper, get(client, base, "/api/v1/full-startup-missing", admin),
                        404, "ROUTE_NOT_FOUND", "/api/v1/full-startup-missing");
            }
            assertThat(rowCounts(environment)).isEqualTo(before);
        } finally {
            if (pool != null) {
                assertThat(pool.isClosed()).isTrue();
            }
            if (port > 0) {
                int closedPort = port;
                assertThatThrownBy(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), closedPort), 500);
                    }
                }).isInstanceOf(java.net.ConnectException.class);
            }
        }
        assertThat(rowCounts(environment)).isEqualTo(before);
    }

    private static HttpResponse<String> get(HttpClient client, String base, String path, String bearer)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5)).header("X-Trace-Id", "full-startup-http").GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void expectProblem(ObjectMapper mapper, HttpResponse<String> response,
                                      int status, String code, String path) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("X-Trace-Id")).contains("full-startup-http");
        var body = mapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("traceId").asText()).isEqualTo("full-startup-http");
    }

    private static Map<String, Long> rowCounts(ConfigurableEnvironment environment) throws Exception {
        ApplicationStartupSupport.verify(environment);
        Map<String, Long> counts = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection(environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"));
             var statement = connection.createStatement()) {
            for (String table : List.of("t_account", "t_account_role", "t_refresh_token", "t_exercise")) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    result.next();
                    counts.put(table, result.getLong(1));
                }
            }
        }
        return counts;
    }

    private static MockEnvironment safeEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", ApplicationStartupSupport.URL)
                .withProperty("spring.datasource.username", "scds_test")
                .withProperty("spring.datasource.password", "test-only")
                .withProperty("server.address", "127.0.0.1").withProperty("server.port", "0")
                .withProperty("spring.profiles.active", ApplicationStartupSupport.PROFILE)
                .withProperty("spring.config.location", "classpath:/application.yml");
    }
}
