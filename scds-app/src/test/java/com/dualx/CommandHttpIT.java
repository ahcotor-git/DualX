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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指挥指令下发的端到端 HTTP 验收（MR-1 的 5 条单兵即时指令 + MR-2 的全体充弹与覆盖配置）。
 *
 * <p>走完整生产入口——真 MySQL、真安全链、真 HTTP、{@code scds.gateway.mode=simulator}——
 * 把 {@code CommandController} 的端点接到真实装配出来的编排服务与仿真链路上，验证
 * 「控制器 → {@code CommandService} → 组帧 → {@code DeviceLinkAdmin} → 队列 + 审计」这条链真的通，
 * 而不只是两侧各自单测通过。编排口径（先发字节后落 SENT、只有链路未开/队列满算未入队、
 * 心跳 OFFLINE 不拦、超范围不写日志）由 {@code CommandService} 决定，本类补的是端到端形状：
 * 下行字节逐字节等于 {@link DownlinkCommands} 的输出、库里的状态与审计行、
 * 以及「无演习 409 / 不在编成 404 / 链路未开则受理但未入队」这三条只有跨接口才看得见的约定。</p>
 *
 * <p>仿真链路的本机替换（设计 05 §4.6）：{@code SimulatedLinkAdapter} 不起线程、不碰物理串口，
 * 下行同步记录，故断言下行字节是确定性的、不与时间赛跑。仿真通过<b>不</b>得写成现场通过。</p>
 *
 * <p>不写成 {@code @Transactional}：请求走应用自己的线程与连接，测试事务帮不上忙。写入用随机想定名
 * 与专属马甲编号段，并在 {@link #cleanUp()} 里按 id / 编号精确回收。</p>
 */
class CommandHttpIT {

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）、PlanHttpIT（62200+）、ExerciseLifecycleHttpIT（62300+）错开。 */
    private static final int FIRST_VEST_NUM = 62400;
    private static final int LAST_VEST_NUM = 62499;

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
                new MapPropertySource("command-http-it", Map.of("scds.gateway.mode", "simulator")));
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

    /** 链路状态与演习场次都是进程内/全局单例：每个用例都从「链路关、无演习」开始，用例末再收干净。 */
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
    @DisplayName("判死/充弹走真实 HTTP：下行字节逐位等于组帧输出，落 SENT 行与审计；超范围下发但不写审计")
    void commandDownlinkReachesSimulatedLinkAndPersistsSentRowWithAudit() throws Exception {
        int[] vests = startRunningExercise("happy", 2);
        int target = vests[0];
        openSerial();

        // 判死：8B 帧 {00 09 FF <num:2BE> <sum> AF FA}
        String dieHex = Hex.encode(DownlinkCommands.die(target));
        JsonNode killed = json(post("/api/v1/command/vests/" + target + "/kill"));
        assertAck(killed, target, "DIE", true, dieHex);

        // 下行确实穿到了链路：仿真记录逐字节等于组帧输出，马甲号也带过去了
        assertThat(links.sentCommands()).hasSize(1);
        assertThat(links.sentCommands().get(0).vestNum()).isEqualTo(target);
        assertThat(links.sentCommands().get(0).hex()).isEqualTo(dieHex);

        // 队列行：直接以 SENT 入队，sent_at 为 NULL（先发字节、成功后一次性落库）
        Map<String, Object> row = commandRow(createdExercises.getLast(), target, "DIE");
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("sentAt")).isNull();
        assertThat(row.get("downlinkHex")).isEqualTo(dieHex);
        // 审计：DIE 有对应协议类型，落一条 t_exercise_log
        assertThat(logCount(createdExercises.getLast(), "DIE")).isEqualTo(1);
        assertThat(logDownlinkHex(createdExercises.getLast(), "DIE")).isEqualTo(dieHex);

        // 单兵充弹：11B 帧 {01 14 FF <num:2BE> <ammo:2BE> <weapon> <sum> AF FA}
        String reloadHex = Hex.encode(DownlinkCommands.reloadOne(target, 5, DownlinkCommands.WEAPON_PRIMARY));
        JsonNode reloaded = json(post("/api/v1/command/vests/" + target + "/reload",
                mapper.createObjectNode().put("ammo", 5).put("weapon", "PRIMARY")));
        assertAck(reloaded, target, "RELOAD", true, reloadHex);
        assertThat(links.sentCommands()).hasSize(2);
        assertThat(links.sentCommands().get(1).hex()).isEqualTo(reloadHex);
        // 参数快照按语义断言，不锁 Jackson 的空白格式
        JsonNode payload = mapper.readTree(
                String.valueOf(commandRow(createdExercises.getLast(), target, "RELOAD").get("payloadJson")));
        assertThat(payload.path("ammo").asInt()).isEqualTo(5);
        assertThat(payload.path("weapon").asText()).isEqualTo("PRIMARY");
        assertThat(logCount(createdExercises.getLast(), "RELOAD")).isEqualTo(1);

        // 超范围：照发字节，但协议 LogType 里没有对应值 → 不写 t_exercise_log（只落队列行）
        long logsBefore = totalLogCount(createdExercises.getLast());
        String rangeHex = Hex.encode(DownlinkCommands.outOfRange(target));
        assertAck(json(post("/api/v1/command/vests/" + target + "/out-of-range")), target,
                "OUT_OF_RANGE", true, rangeHex);
        assertThat(links.sentCommands()).hasSize(3);
        assertThat(links.sentCommands().get(2).hex()).isEqualTo(rangeHex);
        assertThat(commandRow(createdExercises.getLast(), target, "OUT_OF_RANGE").get("state"))
                .isEqualTo("SENT");
        assertThat(totalLogCount(createdExercises.getLast())).isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("链路未开：受理但未入队（accepted=false，不落库不写日志）；无演习 409；不在编成 404")
    void closedLinkIsAcceptedWithoutEnqueueAndContextFailuresAreContractShaped() throws Exception {
        // 无演习进行中：语境失败是 409，而不是 500
        assertProblem(post("/api/v1/command/vests/" + FIRST_VEST_NUM + "/kill"),
                409, "EXERCISE_NOT_RUNNING", "/api/v1/command/vests/" + FIRST_VEST_NUM + "/kill");

        int[] vests = startRunningExercise("reject", 1);
        int target = vests[0];

        // 不在编成：编成内没有这个编号 → 404（不存在的编号就够了，不必真的建马甲）
        String absentPath = "/api/v1/command/vests/" + LAST_VEST_NUM + "/kill";
        assertProblem(post(absentPath), 404, "DEVICE_VEST_NOT_FOUND", absentPath);

        // 链路未开：仍是 HTTP 200 的受理回执，但 accepted=false；此时既不入队也不写审计
        long commandsBefore = commandCount(createdExercises.getLast());
        long logsBefore = totalLogCount(createdExercises.getLast());
        String dieHex = Hex.encode(DownlinkCommands.die(target));
        assertAck(json(post("/api/v1/command/vests/" + target + "/kill")), target, "DIE", false, dieHex);
        assertThat(links.sentCommands()).isEmpty();
        assertThat(commandCount(createdExercises.getLast())).isEqualTo(commandsBefore);
        assertThat(totalLogCount(createdExercises.getLast())).isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("全体充弹：11B 广播帧只发一帧，队列行 vest_num=0，审计行 vest_num 为 NULL")
    void reloadAllBroadcastsOneFrameAndAuditsWithoutVestNum() throws Exception {
        String path = "/api/v1/command/reload-all";
        ObjectNode body = mapper.createObjectNode().put("ammo", 30).put("weapon", "SECONDARY");

        // 无演习：广播同属指挥域，语境失败也是 409（契约 reloadAllVests）
        assertProblem(post(path, body), 409, "EXERCISE_NOT_RUNNING", path);
        // 武器槽位不是契约枚举之一：请求体不成立 → 400（不是 500，也不是静默当成 PRIMARY）
        assertProblem(post(path, mapper.createObjectNode().put("ammo", 30).put("weapon", "TURRET")),
                400, "VALIDATION_FAILED", path);

        int[] vests = startRunningExercise("reload-all", 2);
        openSerial();

        String hex = Hex.encode(DownlinkCommands.reloadAll(30, DownlinkCommands.WEAPON_SECONDARY));
        // 回执 vestNum 为 0：广播帧不指向具体马甲（真实编号下界为 1，0 不会与之混淆）
        assertAck(json(post(path, body)), 0, "RELOAD_ALL", true, hex);

        // 「全体」由帧内的 FF FF FF 表达，而不是由调用次数表达 → 只发一帧
        assertThat(links.sentCommands()).hasSize(1);
        assertThat(links.sentCommands().get(0).vestNum()).isEqualTo(0);
        assertThat(links.sentCommands().get(0).hex()).isEqualTo(hex);

        String exerciseId = createdExercises.getLast();
        Map<String, Object> row = commandRow(exerciseId, 0, "RELOAD_ALL");
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("sentAt")).isNull();
        assertThat(row.get("downlinkHex")).isEqualTo(hex);
        JsonNode payload = mapper.readTree(String.valueOf(row.get("payloadJson")));
        assertThat(payload.path("ammo").asInt()).isEqualTo(30);
        assertThat(payload.path("weapon").asText()).isEqualTo("SECONDARY");

        // 审计：RELOAD 一条；日志的 vest_num 记 NULL（广播不指向具体马甲，不是记 0）
        assertThat(logCount(exerciseId, "RELOAD")).isEqualTo(1);
        assertThat(logCountWithNullVest(exerciseId, "RELOAD")).isEqualTo(1);
        assertThat(logDownlinkHex(exerciseId, "RELOAD")).isEqualTo(hex);

        // 编成里有 2 个马甲，但广播帧只有一行的唯一凭据是 t_exercise_command 里的 vest_num=0
        assertThat(commandCount(exerciseId)).isEqualTo(1);
        assertThat(vests).hasSize(2);
    }

    @Test
    @DisplayName("覆盖配置：未传字段取本场编成原值，14B 帧逐位等于组帧输出，只落队列行不写审计")
    void setVestConfigMergesWithRosterAndSendsPollConfigFrame() throws Exception {
        // 无演习：语境判定先于编成判定 → 409（此处的编号不必存在）
        String absentBeforeStart = "/api/v1/command/vests/" + FIRST_VEST_NUM + "/config";
        assertProblem(put(absentBeforeStart, mapper.createObjectNode().put("hp", 80)),
                409, "EXERCISE_NOT_RUNNING", absentBeforeStart);

        // 编成里写进显式原值：mode=MANUAL / ammo1=12 / ammo2=7（hp 稍后由请求覆盖）
        ObjectNode rosterExtra = mapper.createObjectNode()
                .put("hp", 200).put("ammo1", 12).put("ammo2", 7).put("mode", "MANUAL");
        int[] vests = startRunningExercise("config", 2, rosterExtra);
        int target = vests[0];
        openSerial();

        String exerciseId = createdExercises.getLast();
        long logsBefore = totalLogCount(exerciseId);

        // 只传 hp：其余四项必须回落到编成原值，而不是被当成 0 / AUTO
        String path = "/api/v1/command/vests/" + target + "/config";
        String hex = Hex.encode(DownlinkCommands.pollConfig(
                target, DownlinkCommands.MODE_MANUAL, 80, 12, 7, Team.RED));
        assertAck(json(put(path, mapper.createObjectNode().put("hp", 80))), target, "CONFIG", true, hex);

        assertThat(links.sentCommands()).hasSize(1);
        assertThat(links.sentCommands().get(0).vestNum()).isEqualTo(target);
        assertThat(links.sentCommands().get(0).hex()).isEqualTo(hex);

        Map<String, Object> row = commandRow(exerciseId, target, "CONFIG");
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("sentAt")).isNull();
        assertThat(row.get("downlinkHex")).isEqualTo(hex);
        // 参数快照记合并后的生效值，而不是「只传了 hp」这个原始请求
        JsonNode payload = mapper.readTree(String.valueOf(row.get("payloadJson")));
        assertThat(payload.path("mode").asText()).isEqualTo("MANUAL");
        assertThat(payload.path("hp").asInt()).isEqualTo(80);
        assertThat(payload.path("ammo1").asInt()).isEqualTo(12);
        assertThat(payload.path("ammo2").asInt()).isEqualTo(7);
        assertThat(payload.path("team").asText()).isEqualTo("RED");

        // 不写审计：协议 LogType 里没有 CONFIG，也没有理由借用别的类型
        assertThat(totalLogCount(exerciseId)).isEqualTo(logsBefore);

        // 不在编成：与单兵指令同一个 404 码，不因内部改走编成表而换码
        String absentPath = "/api/v1/command/vests/" + LAST_VEST_NUM + "/config";
        assertProblem(put(absentPath, mapper.createObjectNode().put("hp", 80)),
                404, "DEVICE_VEST_NOT_FOUND", absentPath);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 注册 {@code count} 个马甲 + 建想定 + 开演，返回马甲编号；场次记入回收清单。 */
    private static int[] startRunningExercise(String tag, int count) throws Exception {
        return startRunningExercise(tag, count, null);
    }

    /**
     * 同上，但编成额外带上 {@code rosterExtra} 里的字段（如显式 hp / ammo / mode）。
     *
     * <p>必须在<b>开演前</b>写入：想定一旦被演习占用就禁止改动（{@code PLAN_IN_USE}），
     * 而「覆盖配置取编成原值」要验的正是编成里那组值。</p>
     */
    private static int[] startRunningExercise(String tag, int count, ObjectNode rosterExtra) throws Exception {
        int[] vests = new int[count];
        for (int i = 0; i < count; i++) {
            vests[i] = register("cmd-" + tag + "-" + i, i == 0 ? "RED" : "BLUE");
        }
        String planId = createPlan("cmd-" + tag + "-" + UUID.randomUUID(), rosterExtra, vests);
        HttpResponse<byte[]> started = post("/api/v1/exercise/current",
                mapper.createObjectNode().put("planId", planId));
        assertThat(started.statusCode()).isEqualTo(201);
        createdExercises.add(json(started).path("exerciseId").asText());
        return vests;
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

    private static String createPlan(String name, int... vests) throws Exception {
        return createPlan(name, null, vests);
    }

    /** {@code rosterExtra} 为 null 时编成只留 {@code vestNum} 与 {@code team}，其余字段由服务端取默认值。 */
    private static String createPlan(String name, ObjectNode rosterExtra, int... vests) throws Exception {
        HttpResponse<byte[]> created = post("/api/v1/plans", mapper.createObjectNode().put("name", name));
        assertThat(created.statusCode()).isEqualTo(201);
        String planId = json(created).path("planId").asText();
        createdPlans.add(planId);

        ObjectNode roster = mapper.createObjectNode().put("planId", planId);
        ArrayNode entries = roster.putArray("entries");
        for (int vestNum : vests) {
            ObjectNode entry = mapper.createObjectNode().put("vestNum", vestNum).put("team", "RED");
            if (rosterExtra != null) {
                entry.setAll(rosterExtra);
            }
            entries.add(entry);
        }
        assertThat(put("/api/v1/plans/" + planId + "/roster", roster).statusCode()).isEqualTo(200);
        return planId;
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

    // ── 断言与查询 ───────────────────────────────────────────────────────────

    private static void assertAck(JsonNode ack, int vestNum, String command, boolean accepted, String hex) {
        assertThat(ack.path("vestNum").asInt()).isEqualTo(vestNum);
        assertThat(ack.path("command").asText()).isEqualTo(command);
        assertThat(ack.path("accepted").asBoolean()).isEqualTo(accepted);
        assertThat(ack.path("downlinkHex").asText()).isEqualTo(hex);
        if (accepted) {
            assertThat(ack.path("queuedAt").isNull()).isFalse();
        } else {
            assertThat(ack.path("queuedAt").isNull()).isTrue();
        }
    }

    private static Map<String, Object> commandRow(String exerciseId, int vestNum, String command) {
        return jdbc.sql("SELECT state, sent_at, downlink_hex, payload_json FROM t_exercise_command"
                        + " WHERE exercise_id = :id AND vest_num = :vest AND command = :command")
                .param("id", exerciseId).param("vest", vestNum).param("command", command)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("state", rs.getString("state"));
                    row.put("sentAt", rs.getObject("sent_at"));
                    row.put("downlinkHex", rs.getString("downlink_hex"));
                    row.put("payloadJson", rs.getString("payload_json"));
                    return row;
                })
                .optional()
                .orElseThrow(() -> new AssertionError(
                        "队列里没有 " + command + " 的指令行：exercise=" + exerciseId + " vest=" + vestNum));
    }

    private static long commandCount(String exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_command WHERE exercise_id = :id")
                .param("id", exerciseId).query(Long.class).single();
    }

    private static long totalLogCount(String exerciseId) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_log WHERE exercise_id = :id")
                .param("id", exerciseId).query(Long.class).single();
    }

    private static long logCount(String exerciseId, String logType) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_log WHERE exercise_id = :id AND log_type = :type")
                .param("id", exerciseId).param("type", logType).query(Long.class).single();
    }

    /** 统计 {@code vest_num IS NULL} 的日志条数——广播类日志不指向具体马甲，靠这条与「记成 0」区分开。 */
    private static long logCountWithNullVest(String exerciseId, String logType) {
        return jdbc.sql("SELECT COUNT(*) FROM t_exercise_log WHERE exercise_id = :id AND log_type = :type"
                        + " AND vest_num IS NULL")
                .param("id", exerciseId).param("type", logType).query(Long.class).single();
    }

    private static String logDownlinkHex(String exerciseId, String logType) {
        return jdbc.sql("SELECT downlink_hex FROM t_exercise_log WHERE exercise_id = :id AND log_type = :type"
                        + " ORDER BY seq LIMIT 1")
                .param("id", exerciseId).param("type", logType).query(String.class).single();
    }

    // ── 传输 ─────────────────────────────────────────────────────────────────

    private static HttpResponse<byte[]> post(String path) throws Exception {
        return post(path, mapper.createObjectNode());
    }

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
