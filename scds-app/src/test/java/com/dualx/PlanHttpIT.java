package com.dualx;

import com.dualx.core.domain.Account;
import com.dualx.core.domain.BattleMode;
import com.dualx.core.domain.Exercise;
import com.dualx.core.domain.Role;
import com.dualx.core.repository.AccountRepository;
import com.dualx.core.repository.AccountRoleRepository;
import com.dualx.core.repository.ExerciseRepository;
import com.dualx.core.support.UuidV7;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 想定（plan）/ 编成（roster）的 HTTP 接线验收（M2 PLAN）。
 *
 * <p>用完整生产入口启动真实 MySQL 与真实安全链，走真实 TCP：建档 → 换编成 → 取编成 → 改 →
 * 删，以及模板下载 → 整文件导入，验证「控制器确实把 {@code PlanService} 接上了契约路径」。
 * 服务层行为由 {@code PlanServiceIT} / {@code PlanImportIT} 覆盖，本类补的是端到端形状：
 * 状态码与 Problem 里的 code、编成的稳定排序、模板响应的 {@code Content-Type} 与
 * {@code Content-Disposition}、以及「导入期校验失败不留半份想定」这类只有跨接口才看得见的约定。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走的是应用自己的线程与连接，测试事务帮不上忙。
 * 写入用随机想定名与专属马甲编号段，并在 {@link #cleanUp()} 里按 id / 编号精确回收。</p>
 */
class PlanHttpIT {

    private static final String PASSWORD = "plan-http-secret";

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）等既有 IT 错开。 */
    private static final int FIRST_VEST_NUM = 62200;
    private static final int LAST_VEST_NUM = 62299;

    private static ConfigurableApplicationContext context;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static AccountRepository accounts;
    private static AccountRoleRepository accountRoles;
    private static ExerciseRepository exercises;
    private static PasswordEncoder passwords;

    private static final List<UUID> createdAccounts = new ArrayList<>();
    private static final List<Integer> createdVests = new ArrayList<>();
    private static final List<String> createdPlans = new ArrayList<>();
    private static final List<String> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootFullApplication() throws Exception {
        var key = ApplicationStartupSupport.newKeyPair();
        context = ApplicationStartupSupport.start(ApplicationStartupSupport.environment(true, key));
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        base = "http://127.0.0.1:" + port;
        mapper = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcClient.class);
        accounts = context.getBean(AccountRepository.class);
        accountRoles = context.getBean(AccountRoleRepository.class);
        exercises = context.getBean(ExerciseRepository.class);
        passwords = context.getBean(PasswordEncoder.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (context != null) {
            for (String exerciseId : createdExercises) {
                jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
            }
            for (String planId : createdPlans) {
                jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId").param("planId", planId).update();
                jdbc.sql("DELETE FROM t_plan WHERE id = :planId").param("planId", planId).update();
            }
            for (int vestNum : createdVests) {
                jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum").param("vestNum", vestNum).update();
            }
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
    @DisplayName("想定与编成走真实 HTTP：换编成按队伍排序、非法编号整单拒绝、演习中的想定禁改禁删")
    void plansAndRosterOverRealHttp() throws Exception {
        String token = adminToken();
        int redVest = register(token, "编成甲", "RED");
        int blueVest = register(token, "编成乙", "BLUE");

        String name = "http-plan-" + UUID.randomUUID();
        HttpResponse<byte[]> created = post("/api/v1/plans",
                mapper.createObjectNode().put("name", name), token);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode plan = json(created);
        String planId = plan.path("planId").asText();
        createdPlans.add(planId);
        assertThat(plan.path("name").asText()).isEqualTo(name);
        assertThat(plan.path("rosterCount").asInt()).isZero();
        // battleMode 不传即契约默认 TRAIN；mapId 未指定回 null 而不是空串
        assertThat(plan.path("battleMode").asText()).isEqualTo("TRAIN");
        assertThat(plan.path("mapId").isNull()).isTrue();

        // 列表按名称模糊搜索：?keyword= 是「没填」，不该被当成搜空字符串
        assertThat(json(get("/api/v1/plans?keyword=", token)).path("meta").path("total").asInt())
                .isGreaterThanOrEqualTo(1);
        JsonNode found = json(get("/api/v1/plans?keyword=" + url(name), token));
        assertThat(found.path("items").findValuesAsText("planId")).contains(planId);
        // 契约的 size 上限 200 由 @Max 拦在参数校验层，越界不静默截断
        assertProblem(get("/api/v1/plans?size=201", token), 400, "VALIDATION_FAILED", "/api/v1/plans");

        // 换编成：契约要求回显按「队伍 → 马甲编号」排序，与请求体顺序无关
        String rosterPath = "/api/v1/plans/" + planId + "/roster";
        JsonNode replaced = json(put(rosterPath, roster(planId, entry(redVest, "RED"), entry(blueVest, "BLUE")), token));
        assertThat(replaced.path("entries")).hasSize(2);
        assertThat(replaced.path("entries").get(0).path("vestNum").asInt()).isEqualTo(blueVest);
        // 缺省列在接口层补齐后显式落库（不是靠库默认值）
        assertThat(replaced.path("entries").get(1).path("hp").asInt()).isEqualTo(100);
        assertThat(replaced.path("entries").get(1).path("ammo1").asInt()).isEqualTo(30);
        assertThat(replaced.path("entries").get(1).path("mode").asText()).isEqualTo("AUTO");

        // 想定详情的 rosterCount 跟着编成走
        assertThat(json(get("/api/v1/plans/" + planId, token)).path("rosterCount").asInt()).isEqualTo(2);

        // 台账里没有的编号 → 400，且**不产生部分写入**：旧编成原样还在
        assertProblem(put(rosterPath, roster(planId, entry(LAST_VEST_NUM + 1, "RED")), token),
                400, "VALIDATION_FAILED", rosterPath);
        assertThat(json(get(rosterPath, token)).path("entries")).hasSize(2);

        // 同编号跨队 → 400，文案要能指出是哪一号
        HttpResponse<byte[]> crossTeam = put(rosterPath,
                roster(planId, entry(redVest, "RED"), entry(redVest, "BLUE")), token);
        assertProblem(crossTeam, 400, "VALIDATION_FAILED", rosterPath);
        assertThat(crossTeam.body()).asString(StandardCharsets.UTF_8).contains(String.valueOf(redVest));

        // 拼不出来的 planId 折叠成 404 PLAN_NOT_FOUND（与 MapController 同一取向）
        assertProblem(get("/api/v1/plans/not-a-uuid", token), 404, "PLAN_NOT_FOUND", "/api/v1/plans/not-a-uuid");

        // 演习中的想定：改想定 / 删想定 / 换编成一律 409 PLAN_IN_USE
        startRunningExercise(planId, name);
        assertProblem(put("/api/v1/plans/" + planId, mapper.createObjectNode().put("name", name + "-改"), token),
                409, "PLAN_IN_USE", "/api/v1/plans/" + planId);
        assertProblem(delete("/api/v1/plans/" + planId, token), 409, "PLAN_IN_USE", "/api/v1/plans/" + planId);
        assertProblem(put(rosterPath, roster(planId, entry(redVest, "RED")), token),
                409, "PLAN_IN_USE", rosterPath);

        // 停演（清掉进行中标记）之后改与删都恢复
        stopRunningExercises(planId);
        JsonNode renamed = json(put("/api/v1/plans/" + planId,
                mapper.createObjectNode().put("name", name + "-改"), token));
        assertThat(renamed.path("name").asText()).isEqualTo(name + "-改");
        assertThat(renamed.path("rosterCount").asInt()).isEqualTo(2);

        // 删想定：204 空体，且连带清掉编成——再取编成只能是 PLAN_NOT_FOUND
        HttpResponse<byte[]> deleted = delete("/api/v1/plans/" + planId, token);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        createdPlans.remove(planId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM t_plan_roster WHERE plan_id = :planId")
                .param("planId", planId).query(Long.class).single()).isZero();
        assertProblem(get(rosterPath, token), 404, "PLAN_NOT_FOUND", rosterPath);
    }

    @Test
    @DisplayName("模板下载与整文件导入走真实 HTTP：Content-Type/Disposition 按契约、导入校验失败不留半份想定")
    void templateAndImportOverRealHttp() throws Exception {
        String token = adminToken();
        int first = register(token, "导入甲", "RED");
        int second = register(token, "导入乙", "RED");
        int third = register(token, "导入丙", "BLUE");

        // 模板：默认格式是 XLSX，文件名纯 ASCII，避免 Content-Disposition 的中文编码分支
        HttpResponse<byte[]> xlsx = get("/api/v1/plans/template", token);
        assertThat(xlsx.statusCode()).isEqualTo(200);
        assertThat(xlsx.headers().firstValue("content-type").orElse(""))
                .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(xlsx.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains("scds-plan-template.xlsx");
        // PK 魔数：拿到的确实是 xlsx（zip）而不是一段文本
        assertThat(xlsx.body()[0]).isEqualTo((byte) 'P');
        assertThat(xlsx.body()[1]).isEqualTo((byte) 'K');

        HttpResponse<byte[]> csv = get("/api/v1/plans/template?format=CSV", token);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("content-type").orElse("")).startsWith("text/csv");
        // 带 UTF-8 BOM，否则 Windows 版 Excel 按 GBK 打开中文表头会乱码
        assertThat(csv.body()[0]).isEqualTo((byte) 0xEF);
        assertThat(csv.body()[1]).isEqualTo((byte) 0xBB);
        assertThat(csv.body()[2]).isEqualTo((byte) 0xBF);

        // 模板自己导不回来：除示例行外没有数据，属 400 而不是「成功导入一份空想定」
        assertProblem(importPlan("scds-plan-template.csv", csv.body(), null, token),
                400, "VALIDATION_FAILED", "/api/v1/plans/import");

        // 缺 file 部件 → 参数校验层的 400
        assertProblem(importPlan(null, null, null, token), 400, "VALIDATION_FAILED", "/api/v1/plans/import");

        // 不传 name：想定名取文件名去扩展名
        byte[] rosterFile = csv(List.of("马甲编号,姓名,队伍,血量,弹夹1,弹夹2,轮询模式",
                first + ",甲,RED,80,20,10,AUTO",
                second + ",乙,红队,90,,,手动",
                third + ",丙,蓝队,100,30,30,MANUAL"));
        HttpResponse<byte[]> imported = importPlan("plan-import-check.csv", rosterFile, null, token);
        assertThat(imported.statusCode()).isEqualTo(201);
        JsonNode plan = json(imported);
        String planId = plan.path("planId").asText();
        createdPlans.add(planId);
        assertThat(plan.path("name").asText()).isEqualTo("plan-import-check");
        assertThat(plan.path("rosterCount").asInt()).isEqualTo(3);
        // 只导编成：其余属性用契约默认值
        assertThat(plan.path("battleMode").asText()).isEqualTo("TRAIN");
        assertThat(plan.path("mapId").isNull()).isTrue();

        JsonNode entries = json(get("/api/v1/plans/" + planId + "/roster", token)).path("entries");
        assertThat(entries).hasSize(3);
        // 中文队伍名与省略列都按同一套取值规则落库
        assertThat(entries.get(0).path("team").asText()).isEqualTo("BLUE");
        assertThat(entries.get(1).path("hp").asInt()).isEqualTo(80);
        assertThat(entries.get(2).path("mode").asText()).isEqualTo("MANUAL");
        assertThat(entries.get(2).path("hp").asInt()).isEqualTo(90);

        // 表单里的 name 优先于文件名；这里是 multipart 文本部件，靠部件 charset 保中文
        HttpResponse<byte[]> named = importPlan("ignored.csv", csv(List.of("马甲编号,队伍",
                first + ",RED")), "想定导入验收", token);
        assertThat(named.statusCode()).isEqualTo(201);
        String namedId = json(named).path("planId").asText();
        createdPlans.add(namedId);
        assertThat(json(named).path("name").asText()).isEqualTo("想定导入验收");

        // 队伍认不出来 → 400，且一行都不落库（想定总数不变）
        long before = jdbc.sql("SELECT COUNT(*) FROM t_plan").query(Long.class).single();
        assertProblem(importPlan("bad.csv", csv(List.of("马甲编号,队伍", first + ",紫队")), null, token),
                400, "VALIDATION_FAILED", "/api/v1/plans/import");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM t_plan").query(Long.class).single()).isEqualTo(before);

        // 编号不在台账 → 同样整单拒绝
        assertProblem(importPlan("bad.csv", csv(List.of("马甲编号,队伍", (LAST_VEST_NUM + 1) + ",RED")),
                null, token), 400, "VALIDATION_FAILED", "/api/v1/plans/import");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM t_plan").query(Long.class).single()).isEqualTo(before);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static String adminToken() throws Exception {
        UUID accountId = UuidV7.generate();
        String username = "plan-http-" + accountId;
        accounts.insert(new Account(accountId, username, "想定接线账号",
                passwords.encode(PASSWORD), true, null, null, null));
        accountRoles.replaceAll(accountId, List.of(Role.ADMIN));
        createdAccounts.add(accountId);

        HttpResponse<byte[]> login = post("/api/v1/auth/login",
                mapper.createObjectNode().put("username", username).put("password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return json(login).path("accessToken").asText();
    }

    private static int register(String token, String name, String team) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        assertThat(vestNum).isLessThanOrEqualTo(LAST_VEST_NUM);
        createdVests.add(vestNum);
        HttpResponse<byte[]> created = post("/api/v1/devices/vests",
                mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team), token);
        assertThat(created.statusCode()).isEqualTo(201);
        return vestNum;
    }

    private static ObjectNode entry(int vestNum, String team) {
        return mapper.createObjectNode().put("vestNum", vestNum).put("team", team);
    }

    private static ObjectNode roster(String planId, ObjectNode... entries) {
        ObjectNode body = mapper.createObjectNode().put("planId", planId);
        ArrayNode list = body.putArray("entries");
        for (ObjectNode entry : entries) {
            list.add(entry);
        }
        return body;
    }

    /** 造一场进行中的演习：{@code startExercise} 仍是 501，故 409 的场景只能由夹具铺出来。 */
    private static void startRunningExercise(String planId, String planName) {
        UUID exerciseId = UuidV7.generate();
        exercises.insert(new Exercise(exerciseId, UUID.fromString(planId), planName, null, BattleMode.TRAIN,
                Instant.now(), null, 1, 0, 0, null, null));
        createdExercises.add(exerciseId.toString());
    }

    /** 清掉该想定上「进行中」的场次：{@code uk_exercise_running} 是全局的，留着会挡住后面的用例。 */
    private static void stopRunningExercises(String planId) {
        List<String> ended = jdbc.sql("SELECT id FROM t_exercise WHERE plan_id = :planId AND ended_at IS NULL")
                .param("planId", planId).query(String.class).list();
        jdbc.sql("DELETE FROM t_exercise WHERE plan_id = :planId AND ended_at IS NULL")
                .param("planId", planId).update();
        createdExercises.removeAll(ended);
    }

    private static byte[] csv(List<String> lines) {
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    // ── 传输 ─────────────────────────────────────────────────────────────────

    /**
     * 真实 multipart/form-data 导入。
     *
     * <p>{@code name} 是文本部件，带上 {@code charset=UTF-8}：多部件的字符集不随请求头走，
     * 不写这一句服务端会按容器默认编码解，中文想定名会变成乱码——而乱码在断言里看起来
     * 像「服务端没读 name」，实际是夹具没告诉它怎么读。</p>
     */
    private static HttpResponse<byte[]> importPlan(String filename, byte[] content, String name, String bearer)
            throws Exception {
        String boundary = "----scds-plan-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (content != null) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            // 刻意声明 octet-stream：服务端按字节嗅探格式，不看扩展名也不看这个声明
            body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (name != null) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write("Content-Disposition: form-data; name=\"name\"\r\n".getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(name.getBytes(StandardCharsets.UTF_8));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plans/import"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())), bearer);
    }

    private static HttpResponse<byte[]> post(String path, JsonNode body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)), bearer);
    }

    private static HttpResponse<byte[]> put(String path, JsonNode body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)), bearer);
    }

    private static HttpResponse<byte[]> delete(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).DELETE(), bearer);
    }

    private static HttpResponse<byte[]> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET(), bearer);
    }

    private static HttpResponse<byte[]> send(HttpRequest.Builder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 二进制正文按 UTF-8 解回 JSON：读的可能是模板字节，也可能是 Problem 体。 */
    private static JsonNode json(HttpResponse<byte[]> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<byte[]> response, int status, String code, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (Exception failure) {
            throw new AssertionError("问题响应不是合法 JSON："
                    + new String(response.body(), StandardCharsets.UTF_8), failure);
        }
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("instance").asText()).isEqualTo(path);
    }
}
