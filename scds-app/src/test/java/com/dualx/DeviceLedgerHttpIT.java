package com.dualx;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.support.UuidV7;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备台账的 HTTP 接线验收（M2 LEDGER）。
 *
 * <p>用完整生产入口启动真实 MySQL 与真实安全链，走真实 TCP：登记 → 查询 → 修改 → 绑定闭环 →
 * 设备登录 → 注销，验证「控制器确实把 {@code VestLedgerService} / {@code AnchorLedgerService}
 * 接上了契约路径」。服务层行为由 {@code VestLedgerServiceIT} / {@code AnchorLedgerServiceIT}
 * 覆盖，本类补的是端到端形状：状态码、Problem 里的 code、以及密钥不回显这类「只在 HTTP 上
 * 才看得见」的约定。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走的是应用自己的线程与连接，测试事务帮不上忙。
 * 因此所有写入都用专属编号段，并在 {@link #cleanUp()} 里按编号精确回收。</p>
 */
class DeviceLedgerHttpIT {

    private static final String PASSWORD = "ledger-http-secret";
    private static final String BIND_SECRET = "ledger-http-bind-secret";
    private static final String NEW_BIND_SECRET = "ledger-http-new-secret";

    /** 专属编号段：与 DeviceAuthServiceIT（62001+）、VestLedgerServiceIT（62002+）错开。 */
    private static final int FIRST_VEST_NUM = 62100;
    private static final int LAST_VEST_NUM = 62199;

    /** 基站只碰 8 号：bs1~bs4 必开，5~7 留给手工配置，8 号是契约里明确的“可关”位。 */
    private static final int ANCHOR_ID = 8;

    private static ConfigurableApplicationContext context;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static AccountRepository accounts;
    private static AccountRoleRepository accountRoles;
    private static PasswordEncoder passwords;

    private static final List<UUID> createdAccounts = new ArrayList<>();
    private static final List<Integer> createdVests = new ArrayList<>();

    /** 8 号基站的原始形态：用例结束按它还原，避免污染手工配过的坐标。 */
    private static JsonNode originalAnchor;

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
        originalAnchor = anchorRow();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (context != null) {
            for (int vestNum : createdVests) {
                jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum").param("vestNum", vestNum).update();
            }
            restoreAnchor();
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
    @DisplayName("台账 CRUD + 绑定闭环走真实 HTTP：状态码与 code 全按契约")
    void vestLedgerAndBindingOverRealHttp() throws Exception {
        String token = adminToken();
        int vestNum = register(token, "张三", "RED", "LGRHTTP-台账");

        // 重复登记：契约 409 + DEVICE_VEST_NUM_CONFLICT，而不是静默覆盖
        assertThat(post("/api/v1/devices/vests", vestBody(vestNum, "重复", "RED"), token).statusCode())
                .isEqualTo(409);
        assertProblem(post("/api/v1/devices/vests", vestBody(vestNum, "重复", "RED"), token),
                409, "DEVICE_VEST_NUM_CONFLICT", "/api/v1/devices/vests");

        // 详情与列表：摘要字段不得出现在任何响应里（凭据只进不出）
        String detail = get("/api/v1/devices/vests/" + vestNum, token).body();
        assertThat(mapper.readTree(detail).path("name").asText()).isEqualTo("张三");
        assertThat(detail).doesNotContain("bindSecretHash").doesNotContain("secret");
        String listed = get("/api/v1/devices/vests?keyword=" + vestNum, token).body();
        assertThat(mapper.readTree(listed).path("items").get(0).path("vestNum").asInt()).isEqualTo(vestNum);
        assertThat(mapper.readTree(listed).path("meta").path("total").asInt()).isEqualTo(1);

        // 修改：人工列随请求体走，编号不一致是 400
        JsonNode updated = mapper.readTree(
                put("/api/v1/devices/vests/" + vestNum, vestBody(vestNum, "李四", "BLUE"), token).body());
        assertThat(updated.path("name").asText()).isEqualTo("李四");
        assertThat(updated.path("team").asText()).isEqualTo("BLUE");
        JsonNode mismatched = vestBody(vestNum + 1, "李四", "BLUE");
        assertProblem(put("/api/v1/devices/vests/" + vestNum, mismatched, token),
                400, "VALIDATION_FAILED", "/api/v1/devices/vests/" + vestNum);

        // 首次绑定：密钥长度不足先被拦（契约 minLength 8）
        assertProblem(post("/api/v1/devices/vests/" + vestNum + "/binding",
                        mapper.createObjectNode().put("secret", "1234"), token),
                400, "VALIDATION_FAILED", "/api/v1/devices/vests/" + vestNum + "/binding");
        JsonNode bound = mapper.readTree(post("/api/v1/devices/vests/" + vestNum + "/binding",
                bindingBody(BIND_SECRET), token).body());
        assertThat(bound.path("bound").asBoolean()).isTrue();
        assertThat(bound.toString()).doesNotContain(BIND_SECRET);
        // 已绑定再 POST → 409：契约刻意让 POST/PUT 分工不重叠
        assertProblem(post("/api/v1/devices/vests/" + vestNum + "/binding", bindingBody(BIND_SECRET), token),
                409, "DEVICE_VEST_ALREADY_BOUND", "/api/v1/devices/vests/" + vestNum + "/binding");

        // 绑定后的密钥必须真的能换到设备令牌——这是绑定闭环唯一的外部效果
        HttpResponse<String> login = post("/api/v1/auth/device/login",
                mapper.createObjectNode().put("vestNum", vestNum).put("secret", BIND_SECRET), null);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(login.body()).path("deviceToken").asText()).isNotBlank();
        String sidBefore = sessionSid(vestNum);
        assertThat(sidBefore).isNotBlank();

        // 换绑：bound 保持 1，但旧会话被顶掉（sid 换了）
        JsonNode rebound = mapper.readTree(put("/api/v1/devices/vests/" + vestNum + "/binding",
                bindingBody(NEW_BIND_SECRET), token).body());
        assertThat(rebound.path("bound").asBoolean()).isTrue();
        assertThat(sessionSid(vestNum)).isNull();
        assertThat(post("/api/v1/auth/device/login",
                mapper.createObjectNode().put("vestNum", vestNum).put("secret", NEW_BIND_SECRET), null)
                .statusCode()).isEqualTo(200);
        assertThat(sessionSid(vestNum)).isNotBlank().isNotEqualTo(sidBefore);

        // 解绑：bound=0 且幂等（未绑定再调仍是 200）
        JsonNode unbound = mapper.readTree(
                delete("/api/v1/devices/vests/" + vestNum + "/binding", token).body());
        assertThat(unbound.path("bound").asBoolean()).isFalse();
        assertThat(delete("/api/v1/devices/vests/" + vestNum + "/binding", token).statusCode()).isEqualTo(200);
        // 解绑撤销的是「认领」而不是销毁凭据：同一密钥再绑一次仍可用
        assertThat(post("/api/v1/devices/vests/" + vestNum + "/binding", bindingBody(NEW_BIND_SECRET), token)
                .statusCode()).isEqualTo(201);

        // 注销是逻辑删除：204，但行还在，只是 enabled=false
        HttpResponse<String> retired = delete("/api/v1/devices/vests/" + vestNum, token);
        assertThat(retired.statusCode()).isEqualTo(204);
        assertThat(retired.body()).isEmpty();
        assertThat(mapper.readTree(get("/api/v1/devices/vests/" + vestNum, token).body())
                .path("enabled").asBoolean()).isFalse();

        assertProblem(get("/api/v1/devices/vests/" + (LAST_VEST_NUM + 1), token),
                404, "DEVICE_VEST_NOT_FOUND", "/api/v1/devices/vests/" + (LAST_VEST_NUM + 1));
    }

    @Test
    @DisplayName("基站台账走真实 HTTP：可关的号位能关，四角关闭是 400")
    void anchorsOverRealHttp() throws Exception {
        String token = adminToken();

        ObjectNode body = anchorBody("东北角", 0.9, 0.1, true);
        JsonNode saved = mapper.readTree(put("/api/v1/devices/anchors/" + ANCHOR_ID, body, token).body());
        assertThat(saved.path("anchorId").asInt()).isEqualTo(ANCHOR_ID);
        assertThat(saved.path("name").asText()).isEqualTo("东北角");
        assertThat(saved.path("xPx").asDouble()).isEqualTo(0.9);
        assertThat(saved.path("enabled").asBoolean()).isTrue();

        assertThat(mapper.readTree(get("/api/v1/devices/anchors", token).body()).findValuesAsText("anchorId"))
                .isNotEmpty();

        // bs1~bs4 是矩形四角：契约把它们标成「必开」，服务端必须兜住（400 而不是静默存下）
        assertProblem(put("/api/v1/devices/anchors/1", anchorBody("角", 0.1, 0.1, false), token),
                400, "VALIDATION_FAILED", "/api/v1/devices/anchors/1");
        // 编号越界由契约的 1..8 约束拦在参数校验层
        assertProblem(put("/api/v1/devices/anchors/9", anchorBody("越界", 0.1, 0.1, true), token),
                400, "VALIDATION_FAILED", "/api/v1/devices/anchors/9");
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static String adminToken() throws Exception {
        UUID accountId = UuidV7.generate();
        String username = "ledger-http-" + accountId;
        accounts.insert(new Account(accountId, username, "台账接线账号",
                passwords.encode(PASSWORD), true, null, null, null));
        accountRoles.replaceAll(accountId, List.of(Role.ADMIN));
        createdAccounts.add(accountId);

        HttpResponse<String> login = post("/api/v1/auth/login",
                mapper.createObjectNode().put("username", username).put("password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return mapper.readTree(login.body()).path("accessToken").asText();
    }

    private static int register(String token, String name, String team, String remark) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        assertThat(vestNum).isLessThanOrEqualTo(LAST_VEST_NUM);
        createdVests.add(vestNum);
        HttpResponse<String> created = post("/api/v1/devices/vests", vestBody(vestNum, name, team, remark), token);
        assertThat(created.statusCode()).isEqualTo(201);
        return vestNum;
    }

    private static JsonNode vestBody(int vestNum, String name, String team) {
        return vestBody(vestNum, name, team, null);
    }

    private static JsonNode vestBody(int vestNum, String name, String team, String remark) {
        ObjectNode body = mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team);
        if (remark != null) {
            body.put("remark", remark);
        }
        return body;
    }

    private static JsonNode bindingBody(String secret) {
        return mapper.createObjectNode().put("secret", secret);
    }

    private static ObjectNode anchorBody(String name, double xPx, double yPx, boolean enabled) {
        return mapper.createObjectNode().put("name", name).put("xPx", xPx).put("yPx", yPx).put("enabled", enabled);
    }

    private static String sessionSid(int vestNum) {
        return jdbc.sql("SELECT device_session_sid FROM t_vest WHERE vest_num = :vestNum")
                .param("vestNum", vestNum).query(String.class).optional().orElse(null);
    }

    /** 8 号基站的现状（可能压根没档案），JSON 形态便于原样写回。 */
    private static JsonNode anchorRow() {
        return jdbc.sql("SELECT anchor_id, name, x_px, y_px, enabled FROM t_anchor WHERE anchor_id = :anchorId")
                .param("anchorId", ANCHOR_ID)
                .query((rs, rowNum) -> {
                    ObjectNode row = mapper.createObjectNode();
                    row.put("anchor_id", rs.getInt("anchor_id"));
                    row.put("name", rs.getString("name"));
                    row.put("x_px", rs.getObject("x_px", Double.class));
                    row.put("y_px", rs.getObject("y_px", Double.class));
                    row.put("enabled", rs.getBoolean("enabled"));
                    return row;
                })
                .optional()
                .orElse(null);
    }

    private static void restoreAnchor() {
        if (originalAnchor == null) {
            jdbc.sql("DELETE FROM t_anchor WHERE anchor_id = :anchorId").param("anchorId", ANCHOR_ID).update();
            return;
        }
        jdbc.sql("UPDATE t_anchor SET name = :name, x_px = :xPx, y_px = :yPx, enabled = :enabled"
                        + " WHERE anchor_id = :anchorId")
                .param("name", originalAnchor.path("name").asText(null))
                .param("xPx", originalAnchor.path("x_px").isNull() ? null : originalAnchor.path("x_px").asDouble())
                .param("yPx", originalAnchor.path("y_px").isNull() ? null : originalAnchor.path("y_px").asDouble())
                .param("enabled", originalAnchor.path("enabled").asBoolean())
                .param("anchorId", ANCHOR_ID)
                .update();
    }

    private static HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("POST", body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearer);
    }

    private static HttpResponse<String> put(String path, JsonNode body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearer);
    }

    private static HttpResponse<String> delete(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()), bearer);
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET(), bearer);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (Exception failure) {
            throw new AssertionError("问题响应不是合法 JSON：" + response.body(), failure);
        }
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("instance").asText()).isEqualTo(path);
    }
}
