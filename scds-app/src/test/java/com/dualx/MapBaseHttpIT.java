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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 底图图片与签名 URL 的 HTTP 接线验收（M2 MAP-BASE 分段 F/G）。
 *
 * <p>用完整生产入口启动真实 MySQL 与真实安全链，走真实 TCP：建档 → 上传图片（真实
 * multipart）→ 拿签名 URL **不带 Bearer** 取图 → 换图 → 删图，验证「控制器确实把
 * {@code MapLedgerService} / {@code FileController} 接上了契约路径」。服务层行为由
 * {@code MapLedgerServiceIT} 覆盖，本类补的是端到端形状：multipart 传输、签名 URL 的
 * 免鉴权与失效码、响应 {@code Content-Type}、以及「旧图已删」这类只有跨接口才看得见的约定。</p>
 *
 * <p>不写成 {@code @Transactional}——请求走的是应用自己的线程与连接，测试事务帮不上忙。
 * 写入用随机名字 + 用例内 HTTP 删除，{@link #cleanUp()} 再按 id 兜底回收。</p>
 */
class MapBaseHttpIT {

    private static final String PASSWORD = "map-base-http-secret";

    /**
     * 图片落盘根的一次性隔离目录，建在 {@code scds-app/target/} 下（{@code mvn clean} 顺带清掉）。
     *
     * <p>{@code scds.files.root} 留空时是 {@code ${user.home}/.scds/files}：让 IT 往开发机主目录写
     * 底图既污染环境，也让「上传成功」依赖开发机的目录状态。更要紧的是**事务回滚只管数据库、
     * 不会删掉已落盘的文件**，所以这个目录必须一次性且不与人共用。</p>
     */
    private static final String FILES_ROOT =
            Path.of("target", "map-base-http-files", UUID.randomUUID().toString()).toString();

    private static ConfigurableApplicationContext context;
    private static String base;
    private static ObjectMapper mapper;
    private static HttpClient client;
    private static JdbcClient jdbc;
    private static AccountRepository accounts;
    private static AccountRoleRepository accountRoles;
    private static PasswordEncoder passwords;

    private static final List<UUID> createdAccounts = new ArrayList<>();
    private static final List<String> createdMaps = new ArrayList<>();

    @BeforeAll
    static void bootFullApplication() throws Exception {
        KeyPair key = ApplicationStartupSupport.newKeyPair();
        ConfigurableEnvironment environment = ApplicationStartupSupport.environment(true, key);
        // 只加文件存储两项，不动 .env.test 与其它已有配置（见 FILES_ROOT 注释）。
        environment.getPropertySources().addFirst(new MapPropertySource("map-base-http-files",
                Map.of("scds.files.root", FILES_ROOT)));
        context = ApplicationStartupSupport.start(environment);
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
    static void cleanUp() throws Exception {
        if (context != null) {
            for (String mapId : createdMaps) {
                jdbc.sql("DELETE FROM t_map_floor WHERE map_id = :mapId").param("mapId", mapId).update();
                jdbc.sql("DELETE FROM t_map WHERE id = :mapId").param("mapId", mapId).update();
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
        deleteFilesRoot();
    }

    @Test
    @DisplayName("底图图片走真实 HTTP：上传回填宽高、签名 URL 免 Bearer 可取、换图后旧 URL 落到 404")
    void mapImageAndSignedUrlOverRealHttp() throws Exception {
        String token = adminToken();
        String mapId = createMap(token, "签名 URL 验收");

        // 还没上传图片：图在库里、图不在磁盘，两者折叠成同一个 404 MAP_IMAGE_MISSING
        assertProblem(get("/api/v1/maps/" + mapId + "/image", token), 404, "MAP_IMAGE_MISSING",
                "/api/v1/maps/" + mapId + "/image");

        byte[] png = image("png", 37, 19);
        HttpResponse<byte[]> uploaded = upload(mapId, png, "底图.png", token);
        assertThat(uploaded.statusCode()).isEqualTo(200);
        JsonNode afterUpload = json(uploaded);
        // 37×19 而不是正方形：宽高写反在正方形夹具上看不出来
        assertThat(afterUpload.path("imageWidth").asInt()).isEqualTo(37);
        assertThat(afterUpload.path("imageHeight").asInt()).isEqualTo(19);
        String signedUrl = afterUpload.path("imageUrl").asText();
        assertThat(signedUrl).startsWith("/api/v1/files?path=").contains("&exp=").contains("&signature=");

        // 签名 URL 不带 Bearer——<img> 与浏览器不会为图片请求带上 Authorization
        HttpResponse<byte[]> fetched = get(signedUrl, null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.headers().firstValue("content-type").orElse("")).startsWith("image/png");
        assertThat(fetched.body()).isEqualTo(png);

        // 签名错 → 403 FILE_URL_INVALID：与「已过期」共码，客户端处置动作相同（回详情取新 URL）
        assertProblem(get(signedUrl + "A", null), 403, "FILE_URL_INVALID", "/api/v1/files");

        // 带 Bearer 的按域下载路由同样按真实字节回内容类型
        HttpResponse<byte[]> byDomain = get("/api/v1/maps/" + mapId + "/image", token);
        assertThat(byDomain.statusCode()).isEqualTo(200);
        assertThat(byDomain.headers().firstValue("content-type").orElse("")).startsWith("image/png");
        assertThat(byDomain.body()).isEqualTo(png);

        // 不是白名单内的真实图片 → 415：服务层按图片头判，扩展名与请求 Content-Type 都不作数
        assertProblem(upload(mapId, "这不是图片".getBytes(StandardCharsets.UTF_8), "fake.png", token),
                415, "UNSUPPORTED_MEDIA_TYPE", "/api/v1/maps/" + mapId + "/image");

        // 换图：原格式落盘（jpeg 回 image/jpeg），旧文件被删故旧签名 URL 立刻落到 404
        byte[] jpeg = image("jpeg", 20, 10);
        JsonNode replaced = json(upload(mapId, jpeg, "换图.jpg", token));
        assertThat(replaced.path("imageWidth").asInt()).isEqualTo(20);
        assertThat(replaced.path("imageHeight").asInt()).isEqualTo(10);
        String replacedUrl = replaced.path("imageUrl").asText();
        assertThat(replacedUrl).isNotEqualTo(signedUrl);
        assertProblem(get(signedUrl, null), 404, "MAP_IMAGE_MISSING", "/api/v1/files");
        HttpResponse<byte[]> replacedFetched = get(replacedUrl, null);
        assertThat(replacedFetched.statusCode()).isEqualTo(200);
        assertThat(replacedFetched.headers().firstValue("content-type").orElse("")).startsWith("image/jpeg");

        // 删底图：204 且连带删图片文件，签名 URL 随之取不到
        assertThat(delete("/api/v1/maps/" + mapId, token).statusCode()).isEqualTo(204);
        createdMaps.remove(mapId);
        assertProblem(get("/api/v1/maps/" + mapId, token), 404, "MAP_NOT_FOUND", "/api/v1/maps/" + mapId);
        assertProblem(get(replacedUrl, null), 404, "MAP_IMAGE_MISSING", "/api/v1/files");
    }

    @Test
    @DisplayName("楼层点位与 UWB 标定走真实 HTTP：单楼层替换、未标定 404、越界 400")
    void floorPointsAndUwbMetaOverRealHttp() throws Exception {
        String token = adminToken();
        String mapId = createMap(token, "楼层点位验收");
        assertThat(upload(mapId, image("png", 400, 300), "底图.png", token).statusCode()).isEqualTo(200);

        String pointsPath = "/api/v1/maps/" + mapId + "/floors/1/points";
        // 这一层还没标点：正常状态，读回空列表而不是 404。
        // 先断言状态码再解析：出错时响应体是 Problem 对象，path("points") 得到 MissingNode，
        // isEmpty() 一样会通过——那样 500 就被当成「空列表」放过去了。
        HttpResponse<byte[]> beforeAnyPoints = get(pointsPath, token);
        assertThat(beforeAnyPoints.statusCode()).isEqualTo(200);
        assertThat(json(beforeAnyPoints).path("points")).isEmpty();

        ObjectNode points = mapper.createObjectNode();
        points.putArray("points")
                .addObject().put("posNum", 7).put("xPct", 0.25).put("yPct", 0.75).put("label", "西北角");
        assertThat(put(pointsPath, points, token).statusCode()).isEqualTo(200);
        HttpResponse<byte[]> readBackResponse = get(pointsPath, token);
        assertThat(readBackResponse.statusCode()).isEqualTo(200);
        JsonNode readBack = json(readBackResponse);
        assertThat(readBack.path("points")).hasSize(1);
        assertThat(readBack.path("points").get(0).path("posNum").asInt()).isEqualTo(7);
        assertThat(readBack.path("points").get(0).path("label").asText()).isEqualTo("西北角");

        String metaPath = "/api/v1/maps/" + mapId + "/uwb-meta";
        // 图在、只是还没标定 → MAP_NOT_CALIBRATED，与「id 打错了」的 MAP_NOT_FOUND 分码
        assertProblem(get(metaPath, token), 404, "MAP_NOT_CALIBRATED", metaPath);

        assertThat(put(metaPath, uwbMeta(398, 298), token).statusCode()).isEqualTo(200);
        JsonNode detail = json(get("/api/v1/maps/" + mapId, token));
        assertThat(detail.path("calibrated").asBoolean()).isTrue();
        assertThat(detail.path("floorCount").asInt()).isEqualTo(1);
        assertThat(json(get(metaPath, token)).path("realLengthCm").asDouble()).isEqualTo(3000.0);

        // 基站坐标越出底图宽高（400×300）→ 400 MAP_UWB_META_INCOMPLETE
        ObjectNode outOfBounds = uwbMeta(398, 298);
        outOfBounds.withObject("/bs2").put("xPx", 5000);
        assertProblem(put(metaPath, outOfBounds, token), 400, "MAP_UWB_META_INCOMPLETE", metaPath);

        assertThat(delete("/api/v1/maps/" + mapId, token).statusCode()).isEqualTo(204);
        createdMaps.remove(mapId);
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    private static String adminToken() throws Exception {
        UUID accountId = UuidV7.generate();
        String username = "map-base-http-" + accountId;
        accounts.insert(new Account(accountId, username, "底图接线账号",
                passwords.encode(PASSWORD), true, null, null, null));
        accountRoles.replaceAll(accountId, List.of(Role.ADMIN));
        createdAccounts.add(accountId);

        HttpResponse<byte[]> login = post("/api/v1/auth/login",
                mapper.createObjectNode().put("username", username).put("password", PASSWORD), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return json(login).path("accessToken").asText();
    }

    private static String createMap(String token, String name) throws Exception {
        HttpResponse<byte[]> created = post("/api/v1/maps",
                mapper.createObjectNode().put("name", name).put("type", "INDOOR"), token);
        assertThat(created.statusCode()).isEqualTo(201);
        String mapId = json(created).path("mapId").asText();
        createdMaps.add(mapId);
        return mapId;
    }

    /**
     * 真图片字节：PNG/JPEG 都交给 {@code ImageIO} 编码。
     *
     * <p>不手工拼图片头——本类要验的是「传输与验签」这条链路，若连夹具图片都是手搓的，
     * 一旦编码有细微出入，定位会变成猜是夹具错还是服务端错。</p>
     */
    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, format, out);
        return out.toByteArray();
    }

    /** bs1~bs4 落在 {@code maxX × maxY} 之内且不共线的标定参数。 */
    private static ObjectNode uwbMeta(double maxX, double maxY) {
        ObjectNode meta = mapper.createObjectNode();
        meta.set("bs1", point(1, 1));
        meta.set("bs2", point(maxX, 1));
        meta.set("bs3", point(1, maxY));
        meta.set("bs4", point(maxX, maxY));
        meta.put("realLengthCm", 3000);
        meta.put("realWidthCm", 1600);
        return meta;
    }

    private static ObjectNode point(double x, double y) {
        return mapper.createObjectNode().put("xPx", x).put("yPx", y);
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

    /** 真实 multipart/form-data 上传：字段名与 §mapImage 契约一致（{@code file}）。 */
    private static HttpResponse<byte[]> upload(String mapId, byte[] content, String filename, String bearer)
            throws Exception {
        String boundary = "----scds-map-base-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        // 刻意用 application/octet-stream：服务端只认图片头，不认这个声明
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return send(HttpRequest.newBuilder(URI.create(base + "/api/v1/maps/" + mapId + "/image"))
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
                .timeout(Duration.ofSeconds(10))
                .DELETE(), bearer);
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

    /** 二进制正文按 UTF-8 解回 JSON：读的可能是图片路径，也可能是 Problem 体。 */
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
