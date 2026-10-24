package com.dualx;

import com.dualx.api.command.CommandController;
import com.dualx.api.device.DeviceController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.error.NotImplementedException;
import com.dualx.api.exercise.ExerciseController;
import com.dualx.api.map.MapController;
import com.dualx.api.replay.ReplayController;
import com.dualx.api.security.ApiProblemAccessDeniedHandler;
import com.dualx.api.security.ApiProblemAuthenticationEntryPoint;
import com.dualx.api.security.JwtDecoderConfig;
import com.dualx.api.security.SecurityConfig;
import com.dualx.api.situation.SituationController;
import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.ledger.AnchorLedgerService;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.map.MapLedgerService;
import com.dualx.core.plan.PlanService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.FileUrlSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 服务端写权限矩阵回归（设计 03 §4.5；D04 裁定）。
 *
 * <p>只验证「授权层放不放行」，不验证业务实现——骨架期业务控制器一律抛
 * {@code NotImplementedException}，因此放行必然表现为 400/501 等非 401/403 状态。
 * 断言刻意用「非 401/403」而不是具体状态码：本测试的失败语义只有两种，
 * 「被安全链拒绝」或「穿过了安全链」，业务实现落地后不需要改这里。</p>
 *
 * <p>矩阵口径（与 scds-core 的 {@code RolePermissions} 同源，两处改动必须同步）：</p>
 * <ul>
 *   <li>读：VIEWER 及以上一律放行（四个角色都持有 {@code AUD_scds-edge}）。</li>
 *   <li>台账 / 基站：仅 ADMIN。</li>
 *   <li>想定 / 编成 / 演习生命周期 / 地图 / 复盘导出：ADMIN、DIRECTOR。</li>
 *   <li>指令：ADMIN、DIRECTOR、OPERATOR；链路开关：ADMIN、OPERATOR（DIRECTOR 不持
 *       {@code device:link:*}，这条边界最容易被写成「含 DIRECTOR」）。</li>
 * </ul>
 *
 * <p>不接数据库：只导入业务控制器与安全组件，排掉 DataSource 与 Flyway 自动配置。</p>
 */
@SpringBootTest(classes = RoleWritePermissionIT.PermissionApplication.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ActiveProfiles("authz-test")
class RoleWritePermissionIT {

    private static final String ISSUER = "scds-authz-test";
    private static final KeyPair KEY = newKeyPair();
    private static final List<String> ROLES = List.of("ADMIN", "DIRECTOR", "OPERATOR", "VIEWER");
    private static final List<String> ADMIN_ONLY = List.of("ADMIN");
    private static final List<String> DIRECTOR_UP = List.of("ADMIN", "DIRECTOR");
    private static final List<String> COMMAND_UP = List.of("ADMIN", "DIRECTOR", "OPERATOR");
    private static final List<String> LINK_UP = List.of("ADMIN", "OPERATOR");

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtDecoderConfig.class, ApiProblemAuthenticationEntryPoint.class,
            ApiProblemAccessDeniedHandler.class, TraceIdFilter.class, ApiExceptionHandler.class,
            ExerciseController.class, CommandController.class, SituationController.class,
            DeviceController.class, MapController.class, ReplayController.class})
    static class PermissionApplication {

        // 设备控制器已接核心台账服务，而台账服务要 JdbcClient（本切片排掉了 DataSource）。
        // 替身一律抛占位异常：放行的请求因此稳定返回 501 而不是 Mockito 的 null 触发的 500，
        // 本测试只问「授权层放不放行」，不关心服务返回什么。
        @Bean
        VestLedgerService vestLedgerService() {
            return stub(VestLedgerService.class);
        }

        @Bean
        AnchorLedgerService anchorLedgerService() {
            return stub(AnchorLedgerService.class);
        }

        // 底图控制器同理：MapLedgerService 要四个仓储（各自要 JdbcClient），本切片已排掉
        // DataSource。放行的底图写请求因此稳定返回 501；路径参数仍走控制器自己的
        // UUID 解析，填不合法的 id 会得到 404——两者都不是 401/403，不影响本测试判据。
        @Bean
        MapLedgerService mapLedgerService() {
            return stub(MapLedgerService.class);
        }

        // 想定 / 编成 / 模板导入已接核心 PlanService（要四个仓储，各自要 JdbcClient），
        // 本切片已排掉 DataSource。放行的想定请求因此稳定返回 501；
        // 路径参数仍走控制器自己的 UUID 解析，填不合法的 id 会得到 404——都不是 401/403。
        @Bean
        PlanService planService() {
            return stub(PlanService.class);
        }

        // 链路端点已接领域端口 DeviceLinkAdmin（真实现要真串口 / Netty 监听 12001）。
        // 本切片只问「授权层放不放行」，故给替身：放行的开关请求因此稳定返回 400（请求体校验
        // 或端口自身校验）或 501，都不是 401/403，不影响判据。
        @Bean
        DeviceLinkAdmin deviceLinkAdmin() {
            return stub(DeviceLinkAdmin.class);
        }

        // 底图详情要签 imageUrl。签名端口不碰数据库，本可给真实实现，但它依赖
        // scds.files.sign-secret 的默认行为（未配置即随机生成），本切片只问「安全链放不放行」，
        // 真签一份 HMAC 提供不了任何信息——故同样给替身。
        @Bean
        FileUrlSigner fileUrlSigner() {
            return stub(FileUrlSigner.class);
        }

        private static <T> T stub(Class<T> type) {
            return Mockito.mock(type, invocation -> {
                if (invocation.getMethod().getDeclaringClass() == Object.class) {
                    return invocation.callRealMethod();
                }
                throw new NotImplementedException(invocation.getMethod().getName());
            });
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("scds.auth.jwt.issuer", () -> ISSUER);
        registry.add("scds.auth.jwt.signing-enabled", () -> "false");
        registry.add("scds.auth.jwt.public-key", () -> publicKeyPem(KEY));
        registry.add("scds.auth.jwt.public-key-file", () -> "");
        registry.add("scds.auth.jwt.private-key", () -> "");
        registry.add("scds.auth.jwt.private-key-file", () -> "");
        registry.add("scds.auth.jwt.jwks-uri", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /** 契约 {@code docs/api/paths/*.yaml} 里的全部非 GET 路由及其允许的角色。 */
    private static List<WriteRoute> writeRoutes() {
        return List.of(
                // ---- 台账 / 基站：仅 ADMIN ----
                new WriteRoute("POST", "/api/v1/devices/vests", ADMIN_ONLY),
                new WriteRoute("PUT", "/api/v1/devices/vests/1", ADMIN_ONLY),
                new WriteRoute("DELETE", "/api/v1/devices/vests/1", ADMIN_ONLY),
                // 绑定闭环挂在 /vests/{n}/binding 下：`/devices/vests/*` 的通配只吃单段，覆盖不到它。
                new WriteRoute("POST", "/api/v1/devices/vests/1/binding", ADMIN_ONLY),
                new WriteRoute("PUT", "/api/v1/devices/vests/1/binding", ADMIN_ONLY),
                new WriteRoute("DELETE", "/api/v1/devices/vests/1/binding", ADMIN_ONLY),
                new WriteRoute("PUT", "/api/v1/devices/anchors/1", ADMIN_ONLY),
                // ---- 想定 / 编成 ----
                new WriteRoute("POST", "/api/v1/plans", DIRECTOR_UP),
                new WriteRoute("POST", "/api/v1/plans/import", DIRECTOR_UP),
                new WriteRoute("PUT", "/api/v1/plans/p1", DIRECTOR_UP),
                new WriteRoute("DELETE", "/api/v1/plans/p1", DIRECTOR_UP),
                new WriteRoute("PUT", "/api/v1/plans/p1/roster", DIRECTOR_UP),
                // ---- 演习生命周期 ----
                new WriteRoute("POST", "/api/v1/exercise/current", DIRECTOR_UP),
                new WriteRoute("DELETE", "/api/v1/exercise/current", DIRECTOR_UP),
                // ---- 指令（DIRECTOR 持 command:*）----
                new WriteRoute("POST", "/api/v1/command/reload-all", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/kill", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/revive", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/injure", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/out-of-range", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/reload", COMMAND_UP),
                new WriteRoute("PUT", "/api/v1/command/vests/1/config", COMMAND_UP),
                new WriteRoute("POST", "/api/v1/command/vests/1/move", COMMAND_UP),
                // ---- 链路开关（DIRECTOR 不持 device:link:*）----
                new WriteRoute("POST", "/api/v1/devices/links/serial", LINK_UP),
                new WriteRoute("DELETE", "/api/v1/devices/links/serial", LINK_UP),
                new WriteRoute("POST", "/api/v1/devices/links/uwb", LINK_UP),
                new WriteRoute("DELETE", "/api/v1/devices/links/uwb", LINK_UP),
                // ---- 地图 ----
                new WriteRoute("POST", "/api/v1/maps", DIRECTOR_UP),
                new WriteRoute("PUT", "/api/v1/maps/m1", DIRECTOR_UP),
                new WriteRoute("DELETE", "/api/v1/maps/m1", DIRECTOR_UP),
                new WriteRoute("POST", "/api/v1/maps/m1/image", DIRECTOR_UP),
                new WriteRoute("PUT", "/api/v1/maps/m1/floors/1/points", DIRECTOR_UP),
                new WriteRoute("PUT", "/api/v1/maps/m1/uwb-meta", DIRECTOR_UP),
                new WriteRoute("POST", "/api/v1/maps/m1/uwb-calibration", DIRECTOR_UP),
                new WriteRoute("DELETE", "/api/v1/maps/m1/uwb-calibration", DIRECTOR_UP),
                // ---- 复盘导出 ----
                new WriteRoute("POST", "/api/v1/replays/r1/exports", DIRECTOR_UP));
    }

    /** 读接口：VIEWER 及以上，四个角色逐一验证。 */
    private static List<String> readPaths() {
        return List.of("/api/v1/situation", "/api/v1/situation/teams/summary", "/api/v1/situation/hits",
                "/api/v1/devices/vests", "/api/v1/devices/anchors", "/api/v1/devices/links",
                "/api/v1/devices/links/serial-ports", "/api/v1/plans", "/api/v1/plans/template",
                "/api/v1/exercise/current", "/api/v1/exercise/current/logs",
                "/api/v1/maps", "/api/v1/maps/m1", "/api/v1/maps/m1/image",
                "/api/v1/maps/m1/floors/1/points", "/api/v1/maps/m1/uwb-meta",
                "/api/v1/maps/m1/uwb-calibration", "/api/v1/replays", "/api/v1/replays/r1",
                "/api/v1/replays/r1/timeline", "/api/v1/replays/r1/statistics",
                "/api/v1/replays/r1/exports/t1", "/api/v1/replays/r1/exports/t1/download");
    }

    @TestFactory
    Stream<DynamicTest> writeMatrixIsEnforcedPerRole() {
        List<DynamicTest> tests = new ArrayList<>();
        for (WriteRoute route : writeRoutes()) {
            tests.add(dynamicTest(route.method() + " " + route.path(),
                    () -> assertWriteMatrix(route)));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> readsAreOpenToEveryRole() {
        return readPaths().stream().map(path -> dynamicTest("GET " + path, () -> {
            for (String role : ROLES) {
                assertReached(path, role, perform("GET", path, token(role)));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> authorizationLayerShape() {
        return Stream.of(
                dynamicTest("无令牌 → 401", () -> assertProblem(
                        perform("GET", "/api/v1/devices/vests", null),
                        401, "AUTH_TOKEN_INVALID", "/api/v1/devices/vests")),
                dynamicTest("设备令牌写指令 → 403", () -> assertProblem(
                        perform("POST", "/api/v1/command/reload-all", deviceToken()),
                        403, "AUTH_FORBIDDEN", "/api/v1/command/reload-all")),
                dynamicTest("设备令牌只认自身端点，读全量态势 → 403", () -> assertProblem(
                        perform("GET", "/api/v1/situation", deviceToken()),
                        403, "AUTH_FORBIDDEN", "/api/v1/situation")),
                dynamicTest("设备令牌可读自身链路状态 → 放行", () -> assertReached(
                        "/api/v1/situation/vests/1", "device", perform("GET", "/api/v1/situation/vests/1", deviceToken()))),
                dynamicTest("刷新令牌调业务接口 → 403", () -> assertProblem(
                        perform("GET", "/api/v1/devices/vests", token("scds-refresh", List.of())),
                        403, "AUTH_FORBIDDEN", "/api/v1/devices/vests")));
    }

    private void assertWriteMatrix(WriteRoute route) throws Exception {
        for (String role : ROLES) {
            MvcResult result = perform(route.method(), route.path(), token(role));
            if (route.allowedRoles().contains(role)) {
                assertReached(route.path(), role, result);
            } else {
                assertProblem(result, 403, "AUTH_FORBIDDEN", route.path());
            }
        }
    }

    private MvcResult perform(String method, String path, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "PUT" -> put(path).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("未覆盖的 HTTP 方法：" + method);
        };
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    /**
     * 放行的口径是「非 401/403」：本测试只关心请求有没有穿过授权层，
     * 骨架期如实返回 400/501、真实实现后返回 200/404，都不应视为权限回归。
     */
    private void assertReached(String path, String principal, MvcResult result) {
        assertThat(result.getResponse().getStatus())
                .as("%s 的 %s 应被放行（401/403 才代表被安全链拒绝）", principal, path)
                .isNotIn(401, 403);
    }

    private void assertProblem(MvcResult result, int status, String code, String path) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("traceId").asText()).isNotBlank();
    }

    private static String token(String role) throws JOSEException {
        return token("scds-edge", List.of(role));
    }

    private static String deviceToken() throws JOSEException {
        return deviceToken("scds-device");
    }

    private static String token(String audience, List<String> roles) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("roles", roles)
                .build();
        return sign(claims);
    }

    private static String deviceToken(String audience) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("7")
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("sid", UUID.randomUUID().toString())
                .build();
        return sign(claims);
    }

    private static String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("RSA is required for security regression tests", failure);
        }
    }

    private static String publicKeyPem(KeyPair key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** 一条非 GET 路由及其允许的角色集合。 */
    private record WriteRoute(String method, String path, List<String> allowedRoles) {
    }
}
