package com.dualx.api;

import com.dualx.api.auth.AccountAuthController;
import com.dualx.api.auth.AuthController;
import com.dualx.api.command.CommandController;
import com.dualx.api.device.DeviceController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.error.NotImplementedException;
import com.dualx.api.exercise.ExerciseController;
import com.dualx.api.map.MapController;
import com.dualx.api.replay.ReplayController;
import com.dualx.api.situation.SituationController;
import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.auth.AuthService;
import com.dualx.core.auth.DeviceAuthService;
import com.dualx.core.ledger.AnchorLedgerService;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.map.MapLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 骨架冒烟测试：验证「契约已立起」。
 *
 * <p>刻意用 {@code standaloneSetup} 而非 {@code @WebMvcTest}——骨架期要验证的是「路由是否按契约注册」与
 * 「未实现是否统一走 Problem 出口」，这两件事与数据源、Flyway 无关。不启动 Spring 上下文可以让本测试
 * 不依赖本机 MySQL 容器，在任意机器上都能跑。</p>
 *
 * <p>断言口径：契约里的每条路由都必须被映射（未映射时 Spring 返回 404，故断言「非 404」）；
 * 业务实现尚未落地，因此除 404 外的状态码（501 占位 / 400 参数校验）都算通过——把「接口存在」与
 * 「业务已实现」解耦。</p>
 */
class ApiSkeletonSmokeIT {

    private static MockMvc mockMvc;

    /**
     * 底图路由的 {@code {mapId}} 取值。
     *
     * <p>契约把 {@code mapId} 写成 {@code type: string}，但实现里它是 UUIDv7（{@code t_map.id}）：
     * 底图 CRUD 接线后，解析不出来的 id 会被直接判成 {@code MAP_NOT_FOUND}（404）。本测试的
     * 判据恰恰是「非 404」，用 {@code "m1"} 这类占位串会让「路由没注册」与「这张图不存在」
     * 变成同一个状态码，测试随之为已接线端点丧失鉴别力。故填一个语法合法的 UUID。</p>
     */
    private static final String MAP_ID = "018f4a2b-0000-7000-8000-000000000001";

    @BeforeAll
    static void setUpMockMvc() {
        // 账号端点已接核心 AuthService，但本测试只验证「路由按契约注册」、不建立数据库上下文，
        // 因此用一个「一律抛占位异常」的替身：使已接线端点与真占位端点在本测试里响应一致。
        // 真实账号链路（登录 / 滚动刷新 / 退出 / me）由 scds-app 的完整启动测试与认证 IT 覆盖。
        AuthService authService = Mockito.mock(AuthService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 设备登录同理已接核心 DeviceAuthService：真实链路（绑定密钥 / SID 单端在线 / 限流）
        // 由 DeviceAuthServiceIT 与 AuthHttpIT 覆盖，这里只需让它不碰数据库。
        DeviceAuthService deviceAuthService = Mockito.mock(DeviceAuthService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 设备台账与基站台账已接核心服务：本测试不建数据库上下文，故同样用占位替身。
        // 真实链路（过滤 / 分页 / 绑定闭环 / 编号冲突）由 VestLedgerServiceIT、
        // AnchorLedgerServiceIT 与 scds-app 的 DeviceLedgerHttpIT 覆盖。
        VestLedgerService vestLedgerService = Mockito.mock(VestLedgerService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        AnchorLedgerService anchorLedgerService = Mockito.mock(AnchorLedgerService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 底图台账同理：底图 CRUD、楼层点位表与 UWB 标定参数都已接核心 MapLedgerService，
        // 真实链路（楼层数 / 标定齐备判定 / 越界与退化校验 / 被想定引用时 409）由
        // MapLedgerServiceIT 与 scds-app 的 HTTP 端到端覆盖。替身一律抛占位异常，
        // 使已接线端点在本测试里与真占位端点响应一致——本测试只问「路由注册了没有」。
        MapLedgerService mapLedgerService = Mockito.mock(MapLedgerService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountAuthController(authService),
                        new AuthController(authService, deviceAuthService),
                        new ExerciseController(),
                        new CommandController(),
                        new SituationController(),
                        new DeviceController(vestLedgerService, anchorLedgerService),
                        // standalone 不装配 Spring 上下文，ObjectMapper 只能自备：本测试里
                        // 底图相关的 JSON 进出都会被替身的占位异常截断，用默认配置即可。
                        new MapController(mapLedgerService, new ObjectMapper()),
                        new ReplayController())
                .setControllerAdvice(new ApiExceptionHandler())
                // standalone 不装配安全链，@AuthenticationPrincipal 需要显式解析器；
                // 无认证上下文时它解析为 null，由控制器按「令牌不成立」返回 401。
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static Arguments route(String method, String path) {
        return Arguments.of(method, path);
    }

    private static MockHttpServletRequestBuilder request(String method, String path) {
        return switch (method) {
            case "GET" -> MockMvcRequestBuilders.get(path);
            case "POST" -> MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON);
            case "PUT" -> MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON);
            case "DELETE" -> MockMvcRequestBuilders.delete(path);
            default -> throw new IllegalArgumentException("未覆盖的 HTTP 方法：" + method);
        };
    }

    /** 契约 {@code docs/api/paths/*.yaml} 的全部 69 条 REST 路由。 */
    static Stream<Arguments> allContractRoutes() {
        return Stream.of(
                // ---- auth（6）----
                route("POST", "/api/v1/auth/login"),
                route("POST", "/api/v1/auth/refresh"),
                route("POST", "/api/v1/auth/logout"),
                route("GET", "/api/v1/auth/me"),
                route("POST", "/api/v1/auth/device/login"),
                route("GET", "/api/v1/auth/device/me"),
                // ---- exercise：想定 / 编成 / 当前演习（13）----
                route("GET", "/api/v1/plans"),
                route("POST", "/api/v1/plans"),
                route("GET", "/api/v1/plans/template"),
                route("POST", "/api/v1/plans/import"),
                route("GET", "/api/v1/plans/p1"),
                route("PUT", "/api/v1/plans/p1"),
                route("DELETE", "/api/v1/plans/p1"),
                route("GET", "/api/v1/plans/p1/roster"),
                route("PUT", "/api/v1/plans/p1/roster"),
                route("GET", "/api/v1/exercise/current"),
                route("POST", "/api/v1/exercise/current"),
                route("DELETE", "/api/v1/exercise/current"),
                route("GET", "/api/v1/exercise/current/logs"),
                // ---- command（8）----
                route("POST", "/api/v1/command/reload-all"),
                route("POST", "/api/v1/command/vests/1/kill"),
                route("POST", "/api/v1/command/vests/1/revive"),
                route("POST", "/api/v1/command/vests/1/injure"),
                route("POST", "/api/v1/command/vests/1/out-of-range"),
                route("POST", "/api/v1/command/vests/1/reload"),
                route("PUT", "/api/v1/command/vests/1/config"),
                route("POST", "/api/v1/command/vests/1/move"),
                // ---- situation（4）----
                route("GET", "/api/v1/situation"),
                route("GET", "/api/v1/situation/vests/1"),
                route("GET", "/api/v1/situation/teams/summary"),
                route("GET", "/api/v1/situation/hits"),
                // ---- device（17）----
                route("GET", "/api/v1/devices/vests"),
                route("POST", "/api/v1/devices/vests"),
                route("GET", "/api/v1/devices/vests/1"),
                route("PUT", "/api/v1/devices/vests/1"),
                route("DELETE", "/api/v1/devices/vests/1"),
                route("POST", "/api/v1/devices/vests/1/binding"),
                route("PUT", "/api/v1/devices/vests/1/binding"),
                route("DELETE", "/api/v1/devices/vests/1/binding"),
                route("GET", "/api/v1/devices/vests/1/link"),
                route("GET", "/api/v1/devices/anchors"),
                route("PUT", "/api/v1/devices/anchors/a1"),
                route("GET", "/api/v1/devices/links"),
                route("GET", "/api/v1/devices/links/serial-ports"),
                route("POST", "/api/v1/devices/links/serial"),
                route("DELETE", "/api/v1/devices/links/serial"),
                route("POST", "/api/v1/devices/links/uwb"),
                route("DELETE", "/api/v1/devices/links/uwb"),
                // ---- map（14）----
                route("GET", "/api/v1/maps"),
                route("POST", "/api/v1/maps"),
                route("GET", "/api/v1/maps/" + MAP_ID),
                route("PUT", "/api/v1/maps/" + MAP_ID),
                route("DELETE", "/api/v1/maps/" + MAP_ID),
                route("POST", "/api/v1/maps/" + MAP_ID + "/image"),
                route("GET", "/api/v1/maps/" + MAP_ID + "/image"),
                route("GET", "/api/v1/maps/" + MAP_ID + "/floors/1/points"),
                route("PUT", "/api/v1/maps/" + MAP_ID + "/floors/1/points"),
                route("GET", "/api/v1/maps/" + MAP_ID + "/uwb-meta"),
                route("PUT", "/api/v1/maps/" + MAP_ID + "/uwb-meta"),
                route("POST", "/api/v1/maps/" + MAP_ID + "/uwb-calibration"),
                route("GET", "/api/v1/maps/" + MAP_ID + "/uwb-calibration"),
                route("DELETE", "/api/v1/maps/" + MAP_ID + "/uwb-calibration"),
                // ---- replay（7）----
                route("GET", "/api/v1/replays"),
                route("GET", "/api/v1/replays/r1"),
                route("GET", "/api/v1/replays/r1/timeline"),
                route("GET", "/api/v1/replays/r1/statistics"),
                route("POST", "/api/v1/replays/r1/exports"),
                route("GET", "/api/v1/replays/r1/exports/t1"),
                route("GET", "/api/v1/replays/r1/exports/t1/download")
        );
    }

    @ParameterizedTest(name = "{0} {1} 已按契约注册")
    @MethodSource("allContractRoutes")
    @DisplayName("契约中的每条路由都已注册（非 404），且响应带 X-Trace-Id")
    void everyContractRouteIsRegistered(String method, String path) throws Exception {
        mockMvc.perform(request(method, path))
                .andExpect(status().is(not(404)))
                .andExpect(header().exists(TraceIdFilter.HEADER));
    }

    @Test
    @DisplayName("未实现接口统一 501 + application/problem+json + code=NOT_IMPLEMENTED + traceId")
    void notImplementedIsReportedAsProblem() throws Exception {
        String traceId = "smoke-trace-0001";
        // 用仍是真占位的设备端点，而不是已接核心服务的 /auth/me。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/device/me")
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.status").value(501))
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/device/me"))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
