package com.dualx.api;

import com.dualx.api.auth.AuthController;
import com.dualx.api.command.CommandController;
import com.dualx.api.device.DeviceController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.exercise.ExerciseController;
import com.dualx.api.map.MapController;
import com.dualx.api.replay.ReplayController;
import com.dualx.api.situation.SituationController;
import com.dualx.api.support.TraceIdFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
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

    @BeforeAll
    static void setUpMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(),
                        new ExerciseController(),
                        new CommandController(),
                        new SituationController(),
                        new DeviceController(),
                        new MapController(),
                        new ReplayController())
                .setControllerAdvice(new ApiExceptionHandler())
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

    /** 契约 {@code docs/api/paths/*.yaml} 的全部 66 条 REST 路由。 */
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
                // ---- device（14）----
                route("GET", "/api/v1/devices/vests"),
                route("POST", "/api/v1/devices/vests"),
                route("GET", "/api/v1/devices/vests/1"),
                route("PUT", "/api/v1/devices/vests/1"),
                route("DELETE", "/api/v1/devices/vests/1"),
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
                route("GET", "/api/v1/maps/m1"),
                route("PUT", "/api/v1/maps/m1"),
                route("DELETE", "/api/v1/maps/m1"),
                route("POST", "/api/v1/maps/m1/image"),
                route("GET", "/api/v1/maps/m1/image"),
                route("GET", "/api/v1/maps/m1/floors/1/points"),
                route("PUT", "/api/v1/maps/m1/floors/1/points"),
                route("GET", "/api/v1/maps/m1/uwb-meta"),
                route("PUT", "/api/v1/maps/m1/uwb-meta"),
                route("POST", "/api/v1/maps/m1/uwb-calibration"),
                route("GET", "/api/v1/maps/m1/uwb-calibration"),
                route("DELETE", "/api/v1/maps/m1/uwb-calibration"),
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
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.status").value(501))
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/me"))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
