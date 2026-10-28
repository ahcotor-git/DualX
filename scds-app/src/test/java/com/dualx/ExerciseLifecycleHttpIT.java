package com.dualx;

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前演习（{@code /api/v1/exercise/current}）的开停演 HTTP 接线验收（M3 LIFE 分段（二））。
 *
 * <p>用完整生产入口启动真实 MySQL 与真实安全链，走真实 TCP：建想定 → 换编成 → 开演 → 再开演 →
 * 停演 → 再停演，验证「控制器确实把 {@code ExerciseLifecycleService} 接上了契约路径」。
 * 服务层行为（单实例保护、编成校验、停演冻结口径与残留指令作废）由
 * {@code scds-core} 的 {@code ExerciseLifecycleServiceIT} 覆盖，本类补的是端到端形状：
 * 状态码与 Problem 里的 code、{@code onlineCount} 的来源、以及「被拒的开演不留半场次」这类
 * 只有跨接口才看得见的约定。</p>
 *
 * <p><b>刻意不覆盖恢复完成门禁的拒绝路径</b>：真上下文里 {@code ExerciseLivenessRecover} 是
 * {@code ApplicationRunner}，启动完成即已放行门禁，503 在 HTTP 上走不到。那一段由
 * {@code ExerciseLifecycleServiceReadinessTest}（手建未置位实例 + 断言先于查库）与
 * {@code ApiExceptionHandlerBusinessTest}（503 映射）证明。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走的是应用自己的线程与连接，测试事务帮不上忙。
 * 写入用随机想定名与专属马甲编号段，并在 {@link #cleanUp()} 里按 id / 编号精确回收。</p>
 */
class ExerciseLifecycleHttpIT {

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）、PlanHttpIT（62200+）等既有 IT 错开。 */
    private static final int FIRST_VEST_NUM = 62300;
    private static final int LAST_VEST_NUM = 62399;

    private static ConfigurableApplicationContext context;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static String admin;

    private static final List<Integer> createdVests = new ArrayList<>();
    private static final List<String> createdPlans = new ArrayList<>();
    private static final List<String> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootFullApplication() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        context = ApplicationStartupSupport.start(ApplicationStartupSupport.environment(true, key));
        base = "http://127.0.0.1:"
                + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        mapper = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcClient.class);
        // 外部签发的 ADMIN 令牌：本类只验接线，不需要账号夹具，也就不必回收账号。
        admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterAll
    static void cleanUp() {
        if (context != null) {
            for (String exerciseId : createdExercises) {
                // 无外键，顺序无关；逐表按场次精确回收，不碰其他用例的数据。
                // 只有 t_exercise 的主键是 id，其余五张表的外键列都叫 exercise_id。
                for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                        "t_exercise_command", "t_exercise_log")) {
                    jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                            .param("id", exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
            }
            for (String planId : createdPlans) {
                jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId").param("planId", planId).update();
                jdbc.sql("DELETE FROM t_plan WHERE id = :planId").param("planId", planId).update();
            }
            for (int vestNum : createdVests) {
                jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum").param("vestNum", vestNum).update();
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
    @DisplayName("开停演走真实 HTTP：请求里的 battleMode 覆盖想定、开演即在线数为 0、重复开演与无场次停演都是 409")
    void startAndStopExerciseOverRealHttp() throws Exception {
        int redVest = register("开演甲", "RED");
        int blueVest = register("开演乙", "BLUE");
        String planName = "http-lifecycle-" + UUID.randomUUID();
        String planId = createPlan(planName, redVest, blueVest);

        // 无演习进行中：契约明确是 200 + running=false，且不编造场次 ID 与时间
        JsonNode idle = json(get("/api/v1/exercise/current"));
        assertThat(idle.path("running").asBoolean()).isFalse();
        assertThat(idle.path("exerciseId").isNull()).isTrue();
        assertThat(idle.path("elapsedSeconds").isNull()).isTrue();

        // 开演：请求体里的 BATTLE 覆盖想定的 TRAIN，证明「不传才沿用想定」
        HttpResponse<byte[]> started = postExercise(mapper.createObjectNode()
                .put("planId", planId).put("battleMode", "BATTLE"));
        assertThat(started.statusCode()).isEqualTo(201);
        JsonNode running = json(started);
        String exerciseId = running.path("exerciseId").asText();
        createdExercises.add(exerciseId);
        assertThat(running.path("running").asBoolean()).isTrue();
        assertThat(running.path("planId").asText()).isEqualTo(planId);
        assertThat(running.path("planName").asText()).isEqualTo(planName);
        assertThat(running.path("battleMode").asText()).isEqualTo("BATTLE");
        assertThat(running.path("mapId").isNull()).isTrue();
        assertThat(running.path("endedAt").isNull()).isTrue();
        assertThat(running.path("elapsedSeconds").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(running.path("rosterCount").asInt()).isEqualTo(2);
        // 在线数取态势读侧：刚开演时全体刚被装载成 OFFLINE，此刻就是 0（库里没有这一列）
        assertThat(running.path("onlineCount").asInt()).isZero();

        // 读回同一场：GET 与 POST 返回同一场次，rosterCount 来自库、onlineCount 来自态势
        JsonNode current = json(get("/api/v1/exercise/current"));
        assertThat(current.path("running").asBoolean()).isTrue();
        assertThat(current.path("exerciseId").asText()).isEqualTo(exerciseId);
        assertThat(current.path("rosterCount").asInt()).isEqualTo(2);

        // 再开演：唯一索引 uk_exercise_running 只允许一场，翻成契约的 409
        assertProblem(postExercise(mapper.createObjectNode().put("planId", planId)),
                409, "EXERCISE_ALREADY_RUNNING", "/api/v1/exercise/current");

        // 停演：走本端点恒为 NORMAL；复盘落库归 M5，故 replayId 为 null
        HttpResponse<byte[]> stopped = delete("/api/v1/exercise/current");
        assertThat(stopped.statusCode()).isEqualTo(200);
        JsonNode outcome = json(stopped);
        assertThat(outcome.path("exerciseId").asText()).isEqualTo(exerciseId);
        assertThat(outcome.path("endedAt").isNull()).isFalse();
        assertThat(outcome.path("replayId").isNull()).isTrue();
        assertThat(outcome.path("endReason").asText()).isEqualTo("NORMAL");

        // 停演后回到「无演习」，重复停演是 409 而不是 500
        assertThat(json(get("/api/v1/exercise/current")).path("running").asBoolean()).isFalse();
        assertProblem(delete("/api/v1/exercise/current"),
                409, "EXERCISE_NOT_RUNNING", "/api/v1/exercise/current");
    }

    @Test
    @DisplayName("被拒的开演不留半场次：想定不存在 404、编成为空 400、缺 planId 400，事后仍无演习进行中")
    void rejectedStartsLeaveNothingBehind() throws Exception {
        // 拼不出来与查不到都是 404：本次开演引用的想定不存在（开演语境的码，不是 PLAN_NOT_FOUND）
        assertProblem(postExercise(mapper.createObjectNode().put("planId", UuidV7.generate().toString())),
                404, "EXERCISE_PLAN_NOT_FOUND", "/api/v1/exercise/current");
        assertProblem(postExercise(mapper.createObjectNode().put("planId", "not-a-uuid")),
                404, "EXERCISE_PLAN_NOT_FOUND", "/api/v1/exercise/current");

        // 缺少必填 planId 拦在参数校验层，与「想定不存在」是两种客户端动作
        assertProblem(postExercise(mapper.createObjectNode()),
                400, "VALIDATION_FAILED", "/api/v1/exercise/current");

        // 编成为空：是这次请求的内容问题（400），不是「资源不存在」
        int vestNum = register("空编成甲", "RED");
        String emptyPlanId = createPlan("http-lifecycle-empty-" + UUID.randomUUID(), vestNum);
        jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                .param("planId", emptyPlanId).update();
        assertProblem(postExercise(mapper.createObjectNode().put("planId", emptyPlanId)),
                400, "EXERCISE_ROSTER_EMPTY", "/api/v1/exercise/current");

        // 三次被拒都没有留下「进行中」的场次：唯一索引没被占住，读侧的真相也还是「没有演习」
        assertThat(json(get("/api/v1/exercise/current")).path("running").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM t_exercise WHERE plan_id = :planId AND ended_at IS NULL")
                .param("planId", emptyPlanId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("当前演习日志流水：无场次是空页、sinceSeq 游标增量推进、latestSeq 取本批末条")
    void currentLogsFollowTheSeqCursor() throws Exception {
        // 无演习进行中：契约没有 404，是 200 + 空页
        JsonNode idle = json(get("/api/v1/exercise/current/logs"));
        assertThat(idle.path("items")).isEmpty();
        assertThat(idle.path("latestSeq").isNull()).isTrue();

        int vestNum = register("日志甲", "RED");
        String planId = createPlan("http-logs-" + UUID.randomUUID(), vestNum);
        HttpResponse<byte[]> started = postExercise(mapper.createObjectNode().put("planId", planId));
        assertThat(started.statusCode()).isEqualTo(201);
        String exerciseId = json(started).path("exerciseId").asText();
        createdExercises.add(exerciseId);

        // 刚开演尚未写任何日志：仍是空页——证明按「当前场次」过滤，而不是把历史行带出来
        JsonNode empty = json(get("/api/v1/exercise/current/logs"));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("latestSeq").isNull()).isTrue();

        // 种两行：本类只验 HTTP 接线与游标口径，「谁在何时写日志」由服务层与其调用方覆盖
        long first = seedLog(exerciseId, "SHOT", vestNum, "seed-1");
        long second = seedLog(exerciseId, "DIE", vestNum, "seed-2");

        // 不传 sinceSeq = 最新一页：按 seq 升序，latestSeq 取本批末条
        JsonNode latest = json(get("/api/v1/exercise/current/logs"));
        assertThat(latest.path("items")).hasSize(2);
        assertThat(latest.path("items").get(0).path("seq").asLong()).isEqualTo(first);
        assertThat(latest.path("items").get(1).path("seq").asLong()).isEqualTo(second);
        assertThat(latest.path("items").get(0).path("type").asText()).isEqualTo("SHOT");
        assertThat(latest.path("items").get(0).path("vestNum").asInt()).isEqualTo(vestNum);
        assertThat(latest.path("items").get(0).path("detail").asText()).isEqualTo("seed-1");
        assertThat(latest.path("latestSeq").asLong()).isEqualTo(second);

        // 带 sinceSeq：只回 seq 更大的那条，latestSeq 随之推进
        JsonNode delta = json(get("/api/v1/exercise/current/logs?sinceSeq=" + first));
        assertThat(delta.path("items")).hasSize(1);
        assertThat(delta.path("items").get(0).path("seq").asLong()).isEqualTo(second);
        assertThat(delta.path("latestSeq").asLong()).isEqualTo(second);

        // 游标推到末尾：空列表 + latestSeq 为 null，客户端据此判断「已是最新」
        JsonNode drained = json(get("/api/v1/exercise/current/logs?sinceSeq=" + second));
        assertThat(drained.path("items")).isEmpty();
        assertThat(drained.path("latestSeq").isNull()).isTrue();

        // limit 生效：最新一页取 seq 最大的 limit 条，但仍按 seq 升序
        JsonNode limited = json(get("/api/v1/exercise/current/logs?limit=1"));
        assertThat(limited.path("items")).hasSize(1);
        assertThat(limited.path("items").get(0).path("seq").asLong()).isEqualTo(second);

        // 契约边界：limit 越界拦在参数校验层（400），与「路由没注册」的 404 区分开
        assertThat(get("/api/v1/exercise/current/logs?limit=0").statusCode()).isEqualTo(400);

        // 收尾：本类硬约束「同一时刻至多一场演习」，留下进行中的场次会让同类其他用例的
        // 「开演前无演习」前置断言失败；日志行随 createdExercises 在 cleanUp 里按场次回收。
        assertThat(delete("/api/v1/exercise/current").statusCode()).isEqualTo(200);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /**
     * 直插一条日志行并返回库生成的 {@code seq}。
     *
     * <p>{@code t_exercise_log.seq} 由库自增（仓储刻意不提供「插入后取自增值」这条路），
     * 故用 {@code MAX(seq)} 读回；本用例串行执行，不存在与别场的并发插入。</p>
     */
    private static long seedLog(String exerciseId, String logType, Integer vestNum, String detail) {
        jdbc.sql("INSERT INTO t_exercise_log (exercise_id, at, log_type, vest_num, detail)"
                        + " VALUES (:exerciseId, NOW(3), :logType, :vestNum, :detail)")
                .param("exerciseId", exerciseId).param("logType", logType)
                .param("vestNum", vestNum).param("detail", detail).update();
        return jdbc.sql("SELECT MAX(seq) FROM t_exercise_log WHERE exercise_id = :exerciseId")
                .param("exerciseId", exerciseId).query(Long.class).single();
    }

    private static int register(String name, String team) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        assertThat(vestNum).isLessThanOrEqualTo(LAST_VEST_NUM);
        createdVests.add(vestNum);
        HttpResponse<byte[]> created = post("/api/v1/devices/vests",
                mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team));
        assertThat(created.statusCode()).isEqualTo(201);
        return vestNum;
    }

    /** 建想定并按给定编号写编成；{@code vests} 为空即一份空编成的想定。 */
    private static String createPlan(String name, int... vests) throws Exception {
        HttpResponse<byte[]> created = post("/api/v1/plans", mapper.createObjectNode().put("name", name));
        assertThat(created.statusCode()).isEqualTo(201);
        String planId = json(created).path("planId").asText();
        createdPlans.add(planId);

        ObjectNode roster = mapper.createObjectNode().put("planId", planId);
        ArrayNode entries = roster.putArray("entries");
        for (int vestNum : vests) {
            entries.add(mapper.createObjectNode().put("vestNum", vestNum).put("team", "RED"));
        }
        HttpResponse<byte[]> replaced = put("/api/v1/plans/" + planId + "/roster", roster);
        assertThat(replaced.statusCode()).isEqualTo(200);
        return planId;
    }

    private static HttpResponse<byte[]> postExercise(ObjectNode body) throws Exception {
        return post("/api/v1/exercise/current", body);
    }

    // ── 传输 ─────────────────────────────────────────────────────────────────

    private static HttpResponse<byte[]> post(String path, JsonNode body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)));
    }

    private static HttpResponse<byte[]> put(String path, JsonNode body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)));
    }

    private static HttpResponse<byte[]> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).DELETE());
    }

    private static HttpResponse<byte[]> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10)).GET());
    }

    private static HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        request.header("Authorization", "Bearer " + admin);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

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
