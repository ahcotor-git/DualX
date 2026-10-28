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
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复盘导出三端点的 HTTP 端到端验收（M5 REPLAY R4）。
 *
 * <p>用完整生产入口启动真实 MySQL、真实安全链与真实落盘目录，走真实 TCP：
 * 开演 → 停演（得到一条复盘）→ 受理导出（202）→ 等 {@code @Scheduled} 执行器渲染完成 →
 * 带 Bearer 下载拿 {@code Content-Type}/{@code Content-Disposition} → 用产物里的**签名 URL 免 Bearer**
 * 取回同一份字节。服务层行为（合并受理 / 单次性 / 过期 / 渲染失败）由 {@code scds-core} 的
 * {@code ExportTaskServiceIT} 覆盖，本类补的是**接口层形状**：状态码、Problem 的 code、
 * 两种下载通道的 {@code Content-Type} 差异，以及只有跨接口才看得见的约定。</p>
 *
 * <p>唯一与本机环境有关的替换是网关走仿真（{@code scds.gateway.mode=simulator}）：开演要求马甲串口
 * 链路已开（409 {@code DEVICE_LINK_NOT_OPEN}），而本机没有 COM 口。本类不验链路语义，仿真不影响
 * 上述任何断言；仿真通过<b>不</b>得写成现场通过。</p>
 *
 * <p><b>两条下载通道的内容类型刻意不同</b>：{@code ReplayController.downloadExport} 按**文件名后缀**
 * 回 xlsx / text/csv；签名 URL 走 {@code FileController}，它按**真实字节**嗅探，
 * 非图片一律 {@code application/octet-stream}。两者都正确，但不可互相冒充——本类同时断言。</p>
 *
 * <p><b>签名窗口过期也在本类验</b>：签名器持有 {@code Clock.systemUTC()}，HTTP 测试推不动时钟，
 * 故构造「签名本身正确、仅 {@code exp} 已过」的样本要靠**测试侧按契约独立重算**一份签名
 * （固定测试密钥 + {@code 相对路径 + '\n' + exp} 的 HMAC-SHA256）。同一算法先用「未过期」正例
 * 自证与服务端一致，过期样本的 403 才确实是**过期**所致，而不是「签错」。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走的是应用自己的线程与连接，测试事务帮不上忙；
 * 渲染还发生在 {@code @Scheduled} 的线程上。写入用随机编号段与 UUID，并在 {@link #cleanUp()}
 * 里按场次 / 想定 / 编号精确回收。</p>
 */
class ReplayExportHttpIT {

    /** 专属编号段：与 DeviceLedgerHttpIT（62100+）、PlanHttpIT（62200+）、ExerciseLifecycleHttpIT（62300+）错开。 */
    private static final int FIRST_VEST_NUM = 62400;
    private static final int LAST_VEST_NUM = 62499;

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * 固定签名密钥：显式配置后才能由测试按契约独立重算签名，构造「已过期」样本（见 {@link #resignWithExp}）。
     *
     * <p>留空时应用会为本次启动随机生成密钥并打 WARN，测试就拿不到它、只能靠等 15 分钟过 TTL。
     * 显式值需 ≥32 字符，否则应用直接启动失败（{@code FileStorageProperties}）。</p>
     */
    private static final String SIGN_SECRET = "replay-export-http-it-sign-secret-0001";

    /** 产物落盘根的一次性隔离目录；理由同 {@code MapBaseHttpIT.FILES_ROOT}（事务回滚不删已落盘文件）。 */
    private static final String FILES_ROOT =
            Path.of("target", "replay-export-http-files", UUID.randomUUID().toString()).toString();

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
        var environment = ApplicationStartupSupport.environment(true, key);
        // 只加文件存储（根 + 固定签名密钥）与网关模式三项，不动 .env.test 与其它已有配置。
        // 网关走仿真：开演要求马甲串口链路已开（409 `DEVICE_LINK_NOT_OPEN`），而本机没有 COM 口——
        // 与 DeviceSelfHttpIT / CommandHttpIT 同策。本类只验导出，不验链路。
        environment.getPropertySources().addFirst(new MapPropertySource("replay-export-http-files",
                Map.of("scds.files.root", FILES_ROOT,
                        "scds.files.sign-secret", SIGN_SECRET,
                        "scds.gateway.mode", "simulator")));
        context = ApplicationStartupSupport.start(environment);
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
                for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                        "t_exercise_command", "t_exercise_log", "t_export_task")) {
                    jdbc.sql("DELETE FROM " + table + " WHERE " + exerciseColumn(table) + " = :id")
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
        deleteFilesRoot();
    }

    /** 只有 {@code t_exercise} 的主键叫 {@code id}，其余五张站外键列都叫 {@code exercise_id}；导出任务表叫 {@code replay_id}。 */
    private static String exerciseColumn(String table) {
        return "t_export_task".equals(table) ? "replay_id" : "exercise_id";
    }

    @Test
    @DisplayName("XLSX 导出走真实 HTTP：进行中 404、受理 202 同格式合并、执行器渲染、两条下载通道内容类型不同")
    void xlsxExportOverRealHttp() throws Exception {
        int redVest = register("导出甲", "RED");
        int blueVest = register("导出乙", "BLUE");
        String planId = createPlan("http-export-xlsx-" + UUID.randomUUID(), redVest, blueVest);

        openSerial();
        HttpResponse<byte[]> started = post("/api/v1/exercise/current",
                mapper.createObjectNode().put("planId", planId), admin);
        assertThat(started.statusCode()).isEqualTo(201);
        String replayId = json(started).path("exerciseId").asText();
        createdExercises.add(replayId);

        // 进行中的场次不是复盘：受理与详情折叠成同一个 404（不区分「在跑」与「id 不存在」）
        assertProblem(post("/api/v1/replays/" + replayId + "/exports",
                mapper.createObjectNode().put("format", "XLSX"), admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + replayId + "/exports");
        assertProblem(get("/api/v1/replays/" + replayId, admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + replayId);

        assertThat(delete("/api/v1/exercise/current", admin).statusCode()).isEqualTo(200);

        // 受理：202 + PENDING，且此刻还没有产物信息（诚实回答「还没有」，不预填假值）
        HttpResponse<byte[]> accepted = post("/api/v1/replays/" + replayId + "/exports",
                mapper.createObjectNode().put("format", "XLSX"), admin);
        assertThat(accepted.statusCode()).isEqualTo(202);
        JsonNode pending = json(accepted);
        String taskId = pending.path("taskId").asText();
        assertThat(taskId).isNotBlank();
        assertThat(pending.path("state").asText()).isEqualTo("PENDING");
        assertThat(pending.path("fileName").isNull()).isTrue();
        assertThat(pending.path("downloadUrl").isNull()).isTrue();
        assertThat(pending.path("expireAt").isNull()).isTrue();
        assertThat(pending.path("errorMessage").isNull()).isTrue();

        // 同一复盘同一格式再受理：仍是 202。**合并到同一 taskId** 的确定性验证在
        // ExportTaskServiceIT.request_secondCallMergesIntoTheActiveTask（直接落库、不经调度）；
        // 这里刻意不断言 taskId 相等——应用内的 @Scheduled 可能恰在这两次请求之间把首个任务推到
        // SUCCEEDED，此后「再受理」本就该是一条新任务，断言相等会变成一条靠运气的用例。
        HttpResponse<byte[]> again = post("/api/v1/replays/" + replayId + "/exports",
                mapper.createObjectNode().put("format", "XLSX"), admin);
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(json(again).path("taskId").asText()).isNotBlank();

        // 交给应用自己的 @Scheduled 执行器（2s 一拍）渲染，本类只轮询查询端点
        JsonNode succeeded = awaitState(replayId, taskId, "SUCCEEDED");
        String fileName = succeeded.path("fileName").asText();
        assertThat(fileName).startsWith("scds-replay-").endsWith(".xlsx");
        assertThat(succeeded.path("progressPct").asInt()).isEqualTo(100);
        assertThat(succeeded.path("expireAt").isNull()).isFalse();
        assertThat(Instant.parse(succeeded.path("expireAt").asText())).isAfter(Instant.now());
        String downloadUrl = succeeded.path("downloadUrl").asText();
        assertThat(downloadUrl).startsWith("/api/v1/files?path=")
                .contains("&exp=").contains("&signature=");

        // 诚实回答「产物过期」口径：expireAt 是 markSucceeded 时按 TTL 算出的定值，不是每次查询现算
        assertThat(json(get("/api/v1/replays/" + replayId + "/exports/" + taskId, admin))
                .path("expireAt").asText()).isEqualTo(succeeded.path("expireAt").asText());

        // 通道一：按域下载路由带 Bearer，按**文件名后缀**回内容类型并附 Content-Disposition
        String downloadPath = "/api/v1/replays/" + replayId + "/exports/" + taskId + "/download";
        HttpResponse<byte[]> downloaded = get(downloadPath, admin);
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.headers().firstValue("content-type").orElse("")).startsWith(XLSX_MIME);
        assertThat(downloaded.headers().firstValue("content-disposition").orElse(""))
                .contains("attachment").contains(fileName);
        // xlsx 是 zip 容器：PK\x03\x04 开头，能挡住「回了空文件或错误文案却仍 200」
        assertThat(downloaded.body().length).isGreaterThan(0);
        assertThat(Arrays.copyOf(downloaded.body(), 4))
                .isEqualTo(new byte[] {0x50, 0x4B, 0x03, 0x04});

        // 通道二：产物里的签名 URL **免 Bearer**，走 FileController 按真实字节嗅探 → octet-stream
        HttpResponse<byte[]> bySignature = get(downloadUrl, null);
        assertThat(bySignature.statusCode()).isEqualTo(200);
        assertThat(bySignature.headers().firstValue("content-type").orElse(""))
                .startsWith("application/octet-stream");
        assertThat(bySignature.body()).isEqualTo(downloaded.body());

        // 签名被篡改 → 403 FILE_URL_INVALID：免 Bearer 不等于免验签
        assertProblem(get(downloadUrl + "A", null), 403, "FILE_URL_INVALID", "/api/v1/files");

        // 签名窗口过期：先自证重算算法与服务端一致（未过期样本仍 200，取回同一份字节），
        // 再把 exp 拨到过去 —— 签名本身没错，只是过期，落到同一个 403 FILE_URL_INVALID
        // （契约刻意不拆分「签名错」与「已过期」：客户端处置动作相同，区分只会泄露路径是否存在）。
        String notYetExpired = resignWithExp(downloadUrl, Instant.now().plusSeconds(300).getEpochSecond());
        HttpResponse<byte[]> byResigned = get(notYetExpired, null);
        assertThat(byResigned.statusCode()).isEqualTo(200);
        assertThat(byResigned.body()).isEqualTo(downloaded.body());
        String expired = resignWithExp(downloadUrl, Instant.now().minusSeconds(60).getEpochSecond());
        assertProblem(get(expired, null), 403, "FILE_URL_INVALID", "/api/v1/files");

        // 两条导出下载路由都在安全链内：不带 Bearer 一律 401，签名 URL 才是唯一的免鉴权入口
        assertThat(get(downloadPath, null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/replays/" + replayId + "/exports/" + taskId, null).statusCode()).isEqualTo(401);
        assertThat(delete("/api/v1/exercise/current", admin).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("CSV 带 BOM、过期与渲染失败都落 409、跨复盘任务与坏 ID 都是 404、格式校验落 400")
    void csvExpiryAndFailureOverRealHttp() throws Exception {
        String replayA = startAndStop("http-export-csv-a-");
        String replayB = startAndStop("http-export-csv-b-");

        // CSV：纵向长表 + UTF-8 BOM（Excel 打开中文表头不乱码的既有取向）
        String csvTask = requestExport(replayA, "CSV");
        JsonNode csvDone = awaitState(replayA, csvTask, "SUCCEEDED");
        assertThat(csvDone.path("fileName").asText()).endsWith(".csv");
        HttpResponse<byte[]> csv = get(
                "/api/v1/replays/" + replayA + "/exports/" + csvTask + "/download", admin);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("content-type").orElse("")).startsWith("text/csv");
        assertThat(Arrays.copyOf(csv.body(), 3))
                .isEqualTo(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        // 过期：产物仍在磁盘，但 expire_at 已被拨到过去 → 下载 409（与「未完成」「文件丢了」同码）
        assertThat(jdbc.sql("UPDATE t_export_task SET expire_at = NOW(3) - INTERVAL 1 HOUR WHERE id = :id")
                .param("id", csvTask).update()).isEqualTo(1);
        assertProblem(get("/api/v1/replays/" + replayA + "/exports/" + csvTask + "/download", admin),
                409, "REPLAY_EXPORT_FAILED",
                "/api/v1/replays/" + replayA + "/exports/" + csvTask + "/download");

        // 渲染失败：受理后复盘行被删，执行器取不到复盘 → FAILED；查询仍 200（任务还在），下载才是 409
        String doomed = requestExport(replayB, "XLSX");
        deleteReplayRows(replayB);
        JsonNode failed = awaitState(replayB, doomed, "FAILED");
        assertThat(failed.path("errorMessage").asText()).contains("REPLAY_NOT_FOUND");
        assertProblem(get("/api/v1/replays/" + replayB + "/exports/" + doomed + "/download", admin),
                409, "REPLAY_EXPORT_FAILED",
                "/api/v1/replays/" + replayB + "/exports/" + doomed + "/download");

        // 任务归属按 replayId 校验：拿 A 的复盘查 B 的任务 → 404，不串场次
        assertProblem(get("/api/v1/replays/" + replayA + "/exports/" + doomed, admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + replayA + "/exports/" + doomed);

        // 不存在的任务 / 拼不出来的 taskId / 不存在的复盘：一律折叠成同一个 404
        String unknownTask = UuidV7.generate().toString();
        assertProblem(get("/api/v1/replays/" + replayA + "/exports/" + unknownTask, admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + replayA + "/exports/" + unknownTask);
        assertProblem(get("/api/v1/replays/" + replayA + "/exports/not-a-uuid", admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + replayA + "/exports/not-a-uuid");
        String unknownReplay = UuidV7.generate().toString();
        assertProblem(post("/api/v1/replays/" + unknownReplay + "/exports",
                mapper.createObjectNode().put("format", "XLSX"), admin),
                404, "REPLAY_NOT_FOUND", "/api/v1/replays/" + unknownReplay + "/exports");

        // 参数层：缺 format 与枚举外的格式都是 400，与「复盘不存在」是两种客户端动作
        String exportsPath = "/api/v1/replays/" + replayA + "/exports";
        assertProblem(post(exportsPath, mapper.createObjectNode(), admin),
                400, "VALIDATION_FAILED", exportsPath);
        assertProblem(post(exportsPath, mapper.createObjectNode().put("format", "PDF"), admin),
                400, "VALIDATION_FAILED", exportsPath);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 开演再停演，返回复盘 id（复盘与演习是同一行，`replayId == exerciseId`）。 */
    private static String startAndStop(String planNamePrefix) throws Exception {
        String planId = createPlan(planNamePrefix + UUID.randomUUID(), register("导出丙", "RED"));
        openSerial();
        HttpResponse<byte[]> started = post("/api/v1/exercise/current",
                mapper.createObjectNode().put("planId", planId), admin);
        assertThat(started.statusCode()).isEqualTo(201);
        String exerciseId = json(started).path("exerciseId").asText();
        createdExercises.add(exerciseId);
        assertThat(delete("/api/v1/exercise/current", admin).statusCode()).isEqualTo(200);
        return exerciseId;
    }

    /**
     * 开马甲串口（仿真）——契约 {@code startExercise} 的硬前置就是它已 {@code OPEN}。
     *
     * <p>先关后开：开链路端点对「已开」是 409（不是幂等），而一个用例里可能开演两次，
     * 故先归零再开，任何入口都拿到 200。</p>
     */
    private static void openSerial() throws Exception {
        delete("/api/v1/devices/links/serial", admin);
        assertThat(post("/api/v1/devices/links/serial",
                mapper.createObjectNode().put("portName", "COM7"), admin).statusCode()).isEqualTo(200);
    }

    /**
     * 用固定测试密钥重签同一条文件 URL，只把 {@code exp} 换成给定值（见 {@link #SIGN_SECRET}）。
     *
     * <p>不靠推进时钟：HTTP 用例共用真实应用上下文，签名器持有 {@code Clock.systemUTC()}，测试改不动它。
     * 这里按契约在测试侧独立重算——{@code 相对路径 + '\n' + exp} 的 HMAC-SHA256、Base64URL 无填充
     * （见 {@code HmacFileUrlSigner}），既能精确落在过去/未来，又是一份**独立于实现**的判据。
     * 调用方须先用「未过期」样本自证这份重算与服务端一致，过期样本的 403 才说明是过期所致。</p>
     *
     * <p>路径参数**原样沿用**原 URL 里已编码的片段，避免自己编解码出错；参与签名的仍是解码后的相对路径。</p>
     */
    private static String resignWithExp(String signedUrl, long exp) {
        int query = signedUrl.indexOf('?');
        String pathParam = null;
        for (String pair : signedUrl.substring(query + 1).split("&")) {
            if (pair.startsWith("path=")) {
                pathParam = pair;
            }
        }
        if (pathParam == null) {
            throw new IllegalArgumentException("签名 URL 里没有 path 参数：" + signedUrl);
        }
        String rawPath = URLDecoder.decode(pathParam.substring("path=".length()), StandardCharsets.UTF_8);
        String expValue = Long.toString(exp);
        return signedUrl.substring(0, query + 1) + pathParam + "&exp=" + expValue
                + "&signature=" + URLEncoder.encode(hmac(rawPath, expValue), StandardCharsets.UTF_8);
    }

    /** 契约签名：{@code 相对路径 + '\n' + exp} 的 HMAC-SHA256，Base64URL 无填充。 */
    private static String hmac(String relativePath, String exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(relativePath.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(exp.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("测试侧重算文件 URL 签名失败", failure);
        }
    }

    /** 受理一次导出并返回 taskId；断言 202，受理语义本身由上面的用例细验。 */
    private static String requestExport(String replayId, String format) throws Exception {
        HttpResponse<byte[]> accepted = post("/api/v1/replays/" + replayId + "/exports",
                mapper.createObjectNode().put("format", format), admin);
        assertThat(accepted.statusCode()).isEqualTo(202);
        String taskId = json(accepted).path("taskId").asText();
        assertThat(taskId).isNotBlank();
        return taskId;
    }

    /**
     * 轮询查询端点直到任务进入目标状态。
     *
     * <p>不直接调 {@code tick()}：本类是**端到端**验收，要证明接口层拿到的状态确实由应用自己的
     * {@code @Scheduled} 执行器推进（core 侧才用直接调用的方式把调度从断言里摘出去）。</p>
     */
    private static JsonNode awaitState(String replayId, String taskId, String wanted) throws Exception {
        String path = "/api/v1/replays/" + replayId + "/exports/" + taskId;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<byte[]> response = get(path, admin);
            assertThat(response.statusCode()).isEqualTo(200);
            last = json(response);
            String state = last.path("state").asText();
            if (wanted.equals(state)) {
                return last;
            }
            if ("FAILED".equals(state) && !"FAILED".equals(wanted)) {
                throw new AssertionError("等待 " + wanted + " 时任务提前失败：" + last);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待 " + wanted + " 超时（30s），末次响应：" + last);
    }

    /** 精确删除一条复盘的所有从表行与主表行；主表行没了，执行器渲染时就取不到复盘。 */
    private static void deleteReplayRows(String replayId) {
        for (String table : List.of("t_hit_event", "t_track_sample", "t_exercise_unit",
                "t_exercise_command", "t_exercise_log")) {
            jdbc.sql("DELETE FROM " + table + " WHERE exercise_id = :id").param("id", replayId).update();
        }
        jdbc.sql("DELETE FROM t_exercise WHERE id = :id").param("id", replayId).update();
    }

    private static int register(String name, String team) throws Exception {
        int vestNum = FIRST_VEST_NUM + createdVests.size();
        assertThat(vestNum).isLessThanOrEqualTo(LAST_VEST_NUM);
        createdVests.add(vestNum);
        HttpResponse<byte[]> created = post("/api/v1/devices/vests",
                mapper.createObjectNode().put("vestNum", vestNum).put("name", name).put("team", team), admin);
        assertThat(created.statusCode()).isEqualTo(201);
        return vestNum;
    }

    private static String createPlan(String name, int... vests) throws Exception {
        HttpResponse<byte[]> created = post("/api/v1/plans",
                mapper.createObjectNode().put("name", name), admin);
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

    private static void deleteFilesRoot() {
        Path root = Path.of(FILES_ROOT);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不该让一次已经通过的验收变红；留着的是 target/ 下的临时文件。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }

    // ── 传输 ─────────────────────────────────────────────────────────────────

    private static HttpResponse<byte[]> post(String path, JsonNode body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)), bearer);
    }

    private static HttpResponse<byte[]> put(String path, JsonNode body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                        StandardCharsets.UTF_8)), admin);
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

    private static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return mapper.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<byte[]> response, int status, String code, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (IOException failure) {
            throw new AssertionError("问题响应不是合法 JSON："
                    + new String(response.body(), StandardCharsets.UTF_8), failure);
        }
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        // instance 取 requestURI，不含查询串：签名 URL 的失败实例是 /api/v1/files
        assertThat(body.path("instance").asText()).isEqualTo(path);
    }
}
