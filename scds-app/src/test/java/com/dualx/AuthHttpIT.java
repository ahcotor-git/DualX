package com.dualx;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.support.UuidV7;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号认证的 HTTP 接线验收（M1 AUTH-HTTP）。
 *
 * <p>用完整生产入口启动真实 MySQL 与真实安全链，走真实 TCP：登录 → 取自身信息 → 滚动刷新 →
 * 退出，验证「控制器确实把 {@code AuthService} 接上了契约路径」，而不只是服务层单测通过。
 * 路由存在性由 {@code ApiSkeletonSmokeIT} 覆盖，本类补的是端到端行为。</p>
 */
class AuthHttpIT {

    private static final String PASSWORD = "auth-http-secret";

    private static ConfigurableApplicationContext context;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static AccountRepository accounts;
    private static AccountRoleRepository accountRoles;
    private static PasswordEncoder passwords;

    private static final List<UUID> createdAccounts = new ArrayList<>();

    @BeforeAll
    static void bootFullApplication() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        context = ApplicationStartupSupport.start(ApplicationStartupSupport.environment(true, key));
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        base = "http://127.0.0.1:" + port;
        mapper = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcClient.class);
        accounts = context.getBean(AccountRepository.class);
        accountRoles = context.getBean(AccountRoleRepository.class);
        passwords = context.getBean(PasswordEncoder.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void cleanUpSeededAccounts() throws Exception {
        if (context != null) {
            for (UUID id : createdAccounts) {
                jdbc.sql("DELETE FROM t_refresh_token WHERE account_id = :id")
                        .param("id", id.toString()).update();
                accountRoles.deleteByAccountId(id);
                accounts.deleteById(id);
            }
        }
        if (client != null) {
            client.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void loginRotateLogoutAndIdentityOverRealHttp() throws Exception {
        UUID accountId = seedAccount("auth-http-" + UUID.randomUUID());

        HttpResponse<String> login = post("/api/v1/auth/login", loginBody(accountId, PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode issued = mapper.readTree(login.body());
        String access = issued.path("accessToken").asText();
        String refresh = issued.path("refreshToken").asText();
        assertThat(access).isNotBlank();
        assertThat(refresh).isNotBlank().isNotEqualTo(access);
        assertThat(issued.path("expiresIn").asInt()).isEqualTo(1800);
        assertThat(issued.path("user").path("userId").asText()).isEqualTo(accountId.toString());
        assertThat(issued.path("user").path("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(issued.path("user").path("permissions").get(0).asText()).isEqualTo("*");
        assertThat(activeRefreshTokens(accountId)).isEqualTo(1);

        // /auth/me 从本地账号库取身份：userId 必须与库中账号一致，而不是照抄令牌里的 sub
        HttpResponse<String> me = get("/api/v1/auth/me", access);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("username").asText()).isEqualTo(usernameOf(accountId));

        assertProblem(get("/api/v1/auth/me", null), 401, "AUTH_TOKEN_INVALID", "/api/v1/auth/me");

        // 滚动刷新：换发新对并作废旧 refreshToken
        HttpResponse<String> rotated = post("/api/v1/auth/refresh",
                mapper.createObjectNode().put("refreshToken", refresh), null);
        assertThat(rotated.statusCode()).isEqualTo(200);
        JsonNode next = mapper.readTree(rotated.body());
        String access2 = next.path("accessToken").asText();
        String refresh2 = next.path("refreshToken").asText();
        assertThat(access2).isNotBlank().isNotEqualTo(access);
        assertThat(refresh2).isNotBlank().isNotEqualTo(refresh);
        assertThat(activeRefreshTokens(accountId)).isEqualTo(1);

        // 退出：撤销该账号全部 refreshToken；accessToken 无状态，未被注销
        HttpResponse<String> logout = post("/api/v1/auth/logout", null, access2);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(activeRefreshTokens(accountId)).isZero();
        assertThat(get("/api/v1/auth/me", access2).statusCode()).isEqualTo(200);
        assertProblem(post("/api/v1/auth/refresh",
                        mapper.createObjectNode().put("refreshToken", refresh2), null),
                401, "AUTH_TOKEN_INVALID", "/api/v1/auth/refresh");
    }

    @Test
    void replayedRefreshTokenRevokesEveryTokenOfTheAccount() throws Exception {
        UUID accountId = seedAccount("auth-http-replay-" + UUID.randomUUID());

        String refresh = mapper.readTree(
                post("/api/v1/auth/login", loginBody(accountId, PASSWORD), null).body()).path("refreshToken").asText();
        String refresh2 = mapper.readTree(
                post("/api/v1/auth/refresh",
                        mapper.createObjectNode().put("refreshToken", refresh), null).body())
                .path("refreshToken").asText();
        assertThat(activeRefreshTokens(accountId)).isEqualTo(1);

        // 重放已换出的令牌：整条链撤销，连刚换出来的那条一并失效
        assertProblem(post("/api/v1/auth/refresh",
                        mapper.createObjectNode().put("refreshToken", refresh), null),
                401, "AUTH_TOKEN_INVALID", "/api/v1/auth/refresh");
        assertThat(activeRefreshTokens(accountId)).isZero();
        assertProblem(post("/api/v1/auth/refresh",
                        mapper.createObjectNode().put("refreshToken", refresh2), null),
                401, "AUTH_TOKEN_INVALID", "/api/v1/auth/refresh");
    }

    @Test
    void credentialAndPayloadFailuresOverRealHttp() throws Exception {
        UUID accountId = seedAccount("auth-http-bad-" + UUID.randomUUID());

        // 账号不存在与口令错误必须不可区分，否则登录接口就是一个账号枚举器
        assertProblem(post("/api/v1/auth/login", loginBody(accountId, "wrong-secret"), null),
                401, "AUTH_BAD_CREDENTIALS", "/api/v1/auth/login");
        assertProblem(post("/api/v1/auth/login",
                        mapper.createObjectNode()
                                .put("username", "auth-http-missing-" + UUID.randomUUID())
                                .put("password", PASSWORD), null),
                401, "AUTH_BAD_CREDENTIALS", "/api/v1/auth/login");

        assertProblem(post("/api/v1/auth/login", mapper.createObjectNode(), null),
                400, "VALIDATION_FAILED", "/api/v1/auth/login");
        assertProblem(post("/api/v1/auth/refresh", mapper.createObjectNode(), null),
                400, "VALIDATION_FAILED", "/api/v1/auth/refresh");
    }

    private static UUID seedAccount(String username) {
        UUID id = UuidV7.generate();
        accounts.insert(new Account(id, username, "HTTP 接线账号",
                passwords.encode(PASSWORD), true, null, null, null));
        accountRoles.replaceAll(id, List.of(Role.ADMIN));
        createdAccounts.add(id);
        return id;
    }

    private static JsonNode loginBody(UUID accountId, String password) {
        return mapper.createObjectNode()
                .put("username", usernameOf(accountId))
                .put("password", password);
    }

    private static String usernameOf(UUID accountId) {
        return accounts.findById(accountId).orElseThrow().username();
    }

    private static long activeRefreshTokens(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_refresh_token "
                        + "WHERE account_id = :id AND revoked_at IS NULL")
                .param("id", accountId.toString()).query(Long.class).single();
    }

    private static HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5)).header("X-Trace-Id", "auth-http-it")
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.POST(body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5)).header("X-Trace-Id", "auth-http-it").GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code, String path)
            throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("traceId").asText()).isEqualTo("auth-http-it");
    }
}
