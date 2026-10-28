package com.dualx;

import com.dualx.core.support.UuidV7;
import com.dualx.protocol.Team;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单兵自身视角（M4 DEVICE-SELF）的 HTTP 接线端到端验收，覆盖 {@code GET /api/v1/auth/device/me}
 * 与 {@code GET /api/v1/situation/vests/{vestNum}} 在设备令牌下的三条约定：
 *
 * <ul>
 *   <li><b>身份来自台账、状态来自态势</b>：{@code device/me} 的编号 / 名称 / 队伍来自 {@code t_vest}，
 *       {@code state} 与态势域同一模型——有演习且本马甲在编成内时非空，无演习或不在编成内为空；</li>
 *   <li><b>单兵仅自身</b>（决策 04 D04）：设备令牌读他人马甲必须在授权层被拒（403），
 *       而不是靠前端隐藏——这一条是那条约定的可执行形式；</li>
 *   <li><b>单端在线</b>（设计 03 §4.9）：换设备重登顶掉旧会话后，手里的旧令牌<b>不是</b>静默可用，
 *       而是在 {@code device/me} 上被 401 挡下，把客户端引向重新登录。</li>
 * </ul>
 *
 * <p><b>为什么单独一类</b>：{@code RoleWritePermissionIT} 用切片覆盖越权矩阵的<b>形状</b>
 * （状态码与 code），却拿不到真库真态势，证明不了「{@code state} 确实来自本场编成」；
 * {@code DeviceLedgerHttpIT} 覆盖绑定闭环，但不碰态势读侧。本类用完整生产入口把三者串成一条：
 * 真 MySQL、真安全链、真绑定闭环与真演习装载。</p>
 *
 * <p><b>用仿真网关</b>（{@code scds.gateway.mode=simulator}）：本机没有真串口，真链路模式会在启动时
 * 抢占 COM 口与 12001，测试不该依赖硬件在位。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走应用自己的线程与连接，测试事务帮不上忙。因此所有写入
 * 都用专属编号段，并在 {@link #cleanUp()} 里按编号精确回收。</p>
 */
class DeviceSelfHttpIT {

    private static final String BIND_A = "device-self-bind-secret-a";
    private static final String BIND_B = "device-self-bind-secret-b";

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）及服务层 IT（62001/62002）错开。 */
    private static final int FIRST_VEST_NUM = 62300;

    /** 本场想定：两名马甲分属红蓝两队，用于证明「仅自身」是跨队也成立的资源级裁剪。 */
    private static final UUID PLAN_ID = UuidV7.generate();

    private static ConfigurableApplicationContext context;
    private static ObjectMapper mapper;
    private static JdbcClient jdbc;
    private static App app;

    private static int vestA;
    private static int vestB;

    private static final List<Integer> createdVests = new ArrayList<>();
    private static final List<String> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootSeedAndStartExercise() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
        environment.getPropertySources().addFirst(new MapPropertySource(
                "device-self-http-it", Map.of("scds.gateway.mode", "simulator")));
        app = new App(environment, key);
        context = app.context;
        mapper = app.mapper;
        jdbc = context.getBean(JdbcClient.class);

        vestA = register("单兵-甲", "RED");
        vestB = register("单兵-乙", "BLUE");
        bind(vestA, BIND_A);
        bind(vestB, BIND_B);

        seedPlanAndRoster();
        openSerial();
        startExercise();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (app != null) {
            stopIfRunning();
            for (String exerciseId : createdExercises) {
                // 无外键，顺序无关；逐表按场次精确回收，不碰其他用例的数据。
                for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                        "t_exercise_command", "t_exercise_log")) {
                    jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id")
                            .param("id", exerciseId).update();
                }
                jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", exerciseId).update();
            }
            jdbc.sql("DELETE FROM t_plan_roster WHERE plan_id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            jdbc.sql("DELETE FROM t_plan WHERE id = :planId")
                    .param("planId", PLAN_ID.toString()).update();
            for (int vestNum : createdVests) {
                jdbc.sql("DELETE FROM t_vest WHERE vest_num = :vestNum").param("vestNum", vestNum).update();
            }
            app.close();
        }
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("设备令牌读 device/me：身份三项来自台账，state 来自本场编成")
    void deviceSelfMergesLedgerIdentityWithLiveState() throws Exception {
        String deviceToken = deviceLogin(vestA, BIND_A);

        HttpResponse<String> response = app.get("/api/v1/auth/device/me", deviceToken);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode self = mapper.readTree(response.body());

        assertThat(self.path("vestNum").asInt()).isEqualTo(vestA);
        assertThat(self.path("name").asText()).as("名称取自台账，不是态势").isEqualTo("单兵-甲");
        assertThat(self.path("team").asText()).isEqualTo("RED");

        JsonNode state = self.path("state");
        assertThat(state.isNull()).as("演习进行中且本马甲在编成内，state 必须非空").isFalse();
        assertThat(state.path("vestNum").asInt()).isEqualTo(vestA);
        assertThat(state.path("status").asText()).as("装载后未收到帧，马甲应为离线").isEqualTo("OFFLINE");
        assertThat(state.path("hp").asInt()).isEqualTo(100);
        assertThat(state.path("ammo1").asInt()).isEqualTo(30);
    }

    @Test
    @DisplayName("单兵仅自身（决策 04 D04）：设备令牌读自身 200，读他人 403 AUTH_FORBIDDEN")
    void deviceTokenSeesOnlyItsOwnVest() throws Exception {
        String deviceToken = deviceLogin(vestA, BIND_A);

        assertThat(app.get("/api/v1/situation/vests/" + vestA, deviceToken).statusCode())
                .as("读自身实时状态必须放行")
                .isEqualTo(200);

        HttpResponse<String> other = app.get("/api/v1/situation/vests/" + vestB, deviceToken);
        assertProblem(other, 403, "AUTH_FORBIDDEN", "/api/v1/situation/vests/" + vestB);
    }

    @Test
    @DisplayName("单端在线（设计 03 §4.9）：重登顶掉旧会话后，旧设备令牌在 device/me 上被 401 挡下")
    void staleSessionSidIsRejected() throws Exception {
        String staleToken = deviceLogin(vestB, BIND_B);
        // 同一马甲再次登录会把 device_session_sid 换成新的：旧令牌仍在 12h 有效期内，但已不属于当前会话。
        String currentToken = deviceLogin(vestB, BIND_B);

        assertProblem(app.get("/api/v1/auth/device/me", staleToken),
                401, "AUTH_TOKEN_INVALID", "/api/v1/auth/device/me");
        assertThat(app.get("/api/v1/auth/device/me", currentToken).statusCode()).isEqualTo(200);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static int register(String name, String team) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        createdVests.add(vestNum);
        ObjectNode body = mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team);
        HttpResponse<String> created = app.post("/api/v1/devices/vests", body, app.admin);
        assertThat(created.statusCode()).as("登记马甲 %s", vestNum).isEqualTo(201);
        return vestNum;
    }

    private static void bind(int vestNum, String secret) throws Exception {
        HttpResponse<String> bound = app.post("/api/v1/devices/vests/" + vestNum + "/binding",
                mapper.createObjectNode().put("secret", secret), app.admin);
        assertThat(bound.statusCode()).as("绑定马甲 %s", vestNum).isEqualTo(201);
    }

    private static String deviceLogin(int vestNum, String secret) throws Exception {
        HttpResponse<String> login = app.post("/api/v1/auth/device/login",
                mapper.createObjectNode().put("vestNum", vestNum).put("secret", secret), null);
        assertThat(login.statusCode()).as("设备登录 %s", vestNum).isEqualTo(200);
        String token = mapper.readTree(login.body()).path("deviceToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private static void seedPlanAndRoster() {
        jdbc.sql("INSERT INTO t_plan (id, name, map_id, battle_mode, remark)"
                        + " VALUES (:id, :name, NULL, 'TRAIN', NULL)")
                .param("id", PLAN_ID.toString())
                .param("name", "单兵自身IT想定-" + PLAN_ID).update();
        seedRosterRow(vestA, "单兵-甲", Team.RED);
        seedRosterRow(vestB, "单兵-乙", Team.BLUE);
    }

    private static void seedRosterRow(int vestNum, String name, Team team) {
        jdbc.sql("INSERT INTO t_plan_roster (plan_id, vest_num, name, team, hp, ammo1, ammo2, mode)"
                        + " VALUES (:planId, :vestNum, :name, :team, 100, 30, 30, 'AUTO')")
                .param("planId", PLAN_ID.toString())
                .param("vestNum", vestNum).param("name", name).param("team", team.name()).update();
    }

    private static String startExercise() throws Exception {
        HttpResponse<String> response = app.post("/api/v1/exercise/current",
                mapper.createObjectNode().put("planId", PLAN_ID.toString()), app.admin);
        assertThat(response.statusCode()).isEqualTo(201);
        String exerciseId = mapper.readTree(response.body()).path("exerciseId").asText();
        createdExercises.add(exerciseId);
        return exerciseId;
    }

    /** 开马甲串口（仿真）——契约 {@code startExercise} 的硬前置就是它已 {@code OPEN}。 */
    private static void openSerial() throws Exception {
        HttpResponse<String> opened = app.post("/api/v1/devices/links/serial",
                mapper.createObjectNode().put("portName", "COM7"), app.admin);
        assertThat(opened.statusCode()).isEqualTo(200);
    }

    private static void stopIfRunning() throws Exception {
        assertThat(app.delete("/api/v1/exercise/current", app.admin).statusCode()).isIn(200, 409);
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

    /** 一个完整生产入口实例：真 MySQL、真安全链、真 HTTP，以及仿真网关。 */
    private static final class App implements AutoCloseable {

        private final ConfigurableApplicationContext context;
        private final ObjectMapper mapper;
        private final String base;
        private final String admin;
        private final HttpClient http;

        private App(ConfigurableEnvironment environment, KeyPair key) throws Exception {
            this.context = ApplicationStartupSupport.start(environment);
            this.mapper = context.getBean(ObjectMapper.class);
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            this.base = "http://127.0.0.1:" + port;
            this.admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        }

        private HttpResponse<String> post(String path, JsonNode body, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearer);
        }

        private HttpResponse<String> delete(String path, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10)).DELETE(), bearer);
        }

        private HttpResponse<String> get(String path, String bearer) throws Exception {
            return send(HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10)).GET(), bearer);
        }

        private HttpResponse<String> send(HttpRequest.Builder request, String bearer) throws Exception {
            if (bearer != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            http.close();
            context.close();
        }
    }
}
