package com.dualx;

import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.gateway.sim.SimulatedLinkAdapter;
import com.dualx.protocol.Hex;
import com.dualx.protocol.Team;
import com.dualx.protocol.serial.DownlinkCommands;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
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
 * 开演/停演<b>硬件下行</b>的端到端 HTTP 验收（契约 {@code startExercise} 步骤 ③④、
 * {@code stopExercise} 步骤 ③）。
 *
 * <p>服务层（何时发、发什么字节、顺序、失败留痕、链路未开跳过）由 {@code scds-core} 的
 * {@code ExerciseLifecycleServiceDownlinkTest} 覆盖；本类补的是「真装配下这条路真的通」：
 * 走完整生产入口——真 MySQL、真安全链、真 HTTP、{@code scds.gateway.mode=simulator}——
 * 确认控制器 → {@code ExerciseLifecycleService} → {@code DownlinkCommands} 组帧 →
 * {@code DeviceLinkAdmin} 这条链在开演/停演两端都接上了，且下行字节逐位等于组帧输出。
 *
 * <p>仿真链路的本机替换（设计 05 §4.6）：{@code SimulatedLinkAdapter} 不起线程、不碰物理串口，
 * 下行同步记录（{@code sentCommands()} 的先后即发送先后），故断言下行字节是确定性的、
 * 不与时间赛跑。仿真通过<b>不</b>得写成现场通过。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。写入用随机想定名
 * 与专属马甲编号段，并在 {@link #cleanUp()} 里按 id / 编号精确回收。</p>
 */
class ExerciseDownlinkHttpIT {

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）、PlanHttpIT（62200+）、
     * ExerciseLifecycleHttpIT（62300+）、CommandHttpIT（62400+）错开。 */
    private static final int FIRST_VEST_NUM = 62500;
    private static final int LAST_VEST_NUM = 62599;

    private static ConfigurableApplicationContext context;
    private static SimulatedLinkAdapter links;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static String admin;

    private static final List<Integer> createdVests = new ArrayList<>();
    private static final List<String> createdPlans = new ArrayList<>();
    private static final List<String> createdExercises = new ArrayList<>();

    @BeforeAll
    static void bootSimulator() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
        environment.getPropertySources().addFirst(
                new MapPropertySource("exercise-downlink-http-it", Map.of("scds.gateway.mode", "simulator")));
        context = ApplicationStartupSupport.start(environment);
        base = "http://127.0.0.1:"
                + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        mapper = context.getBean(ObjectMapper.class);
        jdbc = context.getBean(JdbcClient.class);
        admin = ApplicationStartupSupport.externalAccessToken(key, List.of("ADMIN"));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        // 装配层确实按 mode 二选一：这条断言成立的下一步，才是「其余断言验的是仿真链路」。
        DeviceLinkAdmin linkAdmin = context.getBean(DeviceLinkAdmin.class);
        assertThat(linkAdmin).isInstanceOf(SimulatedLinkAdapter.class);
        links = (SimulatedLinkAdapter) linkAdmin;
    }

    @AfterAll
    static void cleanUp() {
        if (context != null) {
            for (String exerciseId : createdExercises) {
                // 无外键，顺序无关；逐表按场次精确回收，不碰其他用例的数据。
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

    /** 链路状态与演习场次都是进程内/全局单例：每个用例都从「链路关、无演习」开始。 */
    @BeforeEach
    void resetProcessState() throws Exception {
        closeSerial();
        stopExerciseIfRunning();
        links.clearSentCommands();
    }

    @AfterEach
    void stopExerciseAtEnd() throws Exception {
        stopExerciseIfRunning();
    }

    @Test
    @DisplayName("开演下行：逐台 14B 轮询配置帧后接一台 7B 开始帧，字节逐位等于组帧输出且不留存证")
    void startDownlinkSendsPollConfigPerVestThenStartBattle() throws Exception {
        int red = register("下行甲", "RED");
        int blue = register("下行乙", "BLUE");
        ArrayNode entries = mapper.createArrayNode()
                .add(rosterEntry(red, "RED", 100, 30, 30, "AUTO"))
                .add(rosterEntry(blue, "BLUE", 80, 15, 0, "MANUAL"));
        String planId = createPlan("downlink-start-" + UUID.randomUUID(), entries);
        openSerial();

        HttpResponse<byte[]> started = postExercise(mapper.createObjectNode()
                .put("planId", planId).put("battleMode", "BATTLE"));
        assertThat(started.statusCode()).isEqualTo(201);
        String exerciseId = json(started).path("exerciseId").asText();
        createdExercises.add(exerciseId);

        // 顺序即语义：逐台 14B 配置帧先到，7B 开始帧最后到；sentCommands() 的先后即真实发送先后
        List<SimulatedLinkAdapter.SentCommand> sent = links.sentCommands();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2).vestNum()).isZero();
        assertThat(sent.get(2).hex()).isEqualTo(Hex.encode(
                DownlinkCommands.startBattle(DownlinkCommands.BATTLE_MODE_BATTLE)));
        assertThat(sent.subList(0, 2)).extracting(SimulatedLinkAdapter.SentCommand::vestNum)
                .containsExactlyInAnyOrder(red, blue);
        // 编成读取顺序由仓储的 ORDER BY team, vest_num 决定，不作契约，故这两台按下标之外的编号定位
        assertThat(hexTo(sent, red)).isEqualTo(Hex.encode(DownlinkCommands.pollConfig(
                red, DownlinkCommands.MODE_AUTO, 100, 30, 30, Team.RED)));
        assertThat(hexTo(sent, blue)).isEqualTo(Hex.encode(DownlinkCommands.pollConfig(
                blue, DownlinkCommands.MODE_MANUAL, 80, 15, 0, Team.BLUE)));

        // 链路已开且全部发送成功：不留任何 SYSTEM 存证
        assertThat(totalLogCount(exerciseId)).isZero();
    }

    @Test
    @DisplayName("停演下行：冻结之后补一台 7B 结束帧，广播占位编号为 0，停演本身照常落库")
    void stopDownlinkSendsFinishBattleAfterFreeze() throws Exception {
        int vest = register("停演甲", "RED");
        String planId = createPlan("downlink-stop-" + UUID.randomUUID(),
                mapper.createArrayNode().add(rosterEntry(vest, "RED", 100, 30, 30, "AUTO")));
        openSerial();

        HttpResponse<byte[]> started = postExercise(mapper.createObjectNode().put("planId", planId));
        assertThat(started.statusCode()).isEqualTo(201);
        String exerciseId = json(started).path("exerciseId").asText();
        createdExercises.add(exerciseId);

        // 只关心停演这一段：把开演下发的记录清掉
        links.clearSentCommands();

        HttpResponse<byte[]> stopped = delete("/api/v1/exercise/current");
        assertThat(stopped.statusCode()).isEqualTo(200);
        JsonNode outcome = json(stopped);
        assertThat(outcome.path("endReason").asText()).isEqualTo("NORMAL");
        // replayId 即本场 exerciseId（t_exercise 一行两用），与结束帧是同一场次
        assertThat(outcome.path("replayId").asText()).isEqualTo(exerciseId);

        assertThat(links.sentCommands()).hasSize(1);
        assertThat(links.sentCommands().get(0).vestNum()).isZero();
        assertThat(links.sentCommands().get(0).hex()).isEqualTo(Hex.encode(DownlinkCommands.finishBattle()));
        // 停演已落库完成，硬件是否收到不改变这次停演的事实，也不留系统噪声
        assertThat(totalLogCount(exerciseId)).isZero();
    }

    @Test
    @DisplayName("链路未开：开演与停演都不发字节、不写日志（只 WARN），响应码照常 201/200")
    void downlinkIsSkippedWithoutBytesOrLogsWhenVestLinkIsClosed() throws Exception {
        int vest = register("无链路甲", "RED");
        String planId = createPlan("downlink-closed-" + UUID.randomUUID(),
                mapper.createArrayNode().add(rosterEntry(vest, "RED", 100, 30, 30, "AUTO")));
        // 刻意不开串口链路：{@code @BeforeEach} 的 closeSerial() 已保证它是关的

        HttpResponse<byte[]> started = postExercise(mapper.createObjectNode().put("planId", planId));
        assertThat(started.statusCode()).isEqualTo(201);
        String exerciseId = json(started).path("exerciseId").asText();
        createdExercises.add(exerciseId);

        // 链路未开时下发只会进到无人消费的队列并回报成功（假成功），故一条都不能发；也不写日志
        assertThat(links.sentCommands()).isEmpty();
        assertThat(totalLogCount(exerciseId)).isZero();

        HttpResponse<byte[]> stopped = delete("/api/v1/exercise/current");
        assertThat(stopped.statusCode()).isEqualTo(200);
        assertThat(links.sentCommands()).isEmpty();
        assertThat(totalLogCount(exerciseId)).isZero();
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static int register(String name, String team) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        assertThat(vestNum).isLessThanOrEqualTo(LAST_VEST_NUM);
        createdVests.add(vestNum);
        HttpResponse<byte[]> created = post("/api/v1/devices/vests",
                mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team));
        assertThat(created.statusCode()).isEqualTo(201);
        return vestNum;
    }

    /** 一个编成条目：显式给出五项生效值，好让期望的 14B 帧由这些值现编码得出。 */
    private static ObjectNode rosterEntry(int vestNum, String team, int hp, int ammo1, int ammo2, String mode) {
        return mapper.createObjectNode().put("vestNum", vestNum).put("team", team)
                .put("hp", hp).put("ammo1", ammo1).put("ammo2", ammo2).put("mode", mode);
    }

    /** 建想定并按给定条目写编成；{@code entries} 为每个条目的完整生效值。 */
    private static String createPlan(String name, ArrayNode entries) throws Exception {
        HttpResponse<byte[]> created = post("/api/v1/plans", mapper.createObjectNode().put("name", name));
        assertThat(created.statusCode()).isEqualTo(201);
        String planId = json(created).path("planId").asText();
        createdPlans.add(planId);

        ObjectNode roster = mapper.createObjectNode().put("planId", planId);
        roster.set("entries", entries);
        assertThat(put("/api/v1/plans/" + planId + "/roster", roster).statusCode()).isEqualTo(200);
        return planId;
    }

    private static HttpResponse<byte[]> postExercise(ObjectNode body) throws Exception {
        return post("/api/v1/exercise/current", body);
    }

    private static void openSerial() throws Exception {
        assertThat(post("/api/v1/devices/links/serial",
                mapper.createObjectNode().put("portName", "COM7")).statusCode()).isEqualTo(200);
    }

    /** 关闭马甲串口；已关时端点幂等返回 200，故无需判断当前态。 */
    private static void closeSerial() throws Exception {
        delete("/api/v1/devices/links/serial");
    }

    private static void stopExerciseIfRunning() throws Exception {
        HttpResponse<byte[]> current = get("/api/v1/exercise/current");
        if (current.statusCode() == 200 && json(current).path("running").asBoolean()) {
            delete("/api/v1/exercise/current");
        }
    }

    private static long totalLogCount(String exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_log WHERE exercise_id = :id")
                .param("id", exerciseId).query(Long.class).single();
    }

    /** 取发往某马甲的下行帧十六进制原文；找不到即该马甲没收到帧（断言失败）。 */
    private static String hexTo(List<SimulatedLinkAdapter.SentCommand> sent, int vestNum) {
        return sent.stream().filter(command -> command.vestNum() == vestNum)
                .map(SimulatedLinkAdapter.SentCommand::hex).findFirst()
                .orElseThrow(() -> new AssertionError("没有发往马甲 " + vestNum + " 的下行帧"));
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
}
