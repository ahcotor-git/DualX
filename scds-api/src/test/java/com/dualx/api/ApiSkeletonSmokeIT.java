package com.dualx.api;

import com.dualx.api.auth.AccountAuthController;
import com.dualx.api.auth.AuthController;
import com.dualx.api.command.CommandController;
import com.dualx.api.device.DeviceController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.error.NotImplementedException;
import com.dualx.api.exercise.ExerciseController;
import com.dualx.api.file.FileController;
import com.dualx.api.map.MapController;
import com.dualx.api.replay.ReplayController;
import com.dualx.api.situation.SituationController;
import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.auth.AuthService;
import com.dualx.core.auth.DeviceAuthService;
import com.dualx.core.command.CommandService;
import com.dualx.core.exercise.ExerciseLifecycleService;
import com.dualx.core.ledger.AnchorLedgerService;
import com.dualx.core.ledger.VestLedgerService;
import com.dualx.core.map.MapLedgerService;
import com.dualx.core.map.UwbCalibrationService;
import com.dualx.core.port.DeviceLinkAdmin;
import com.dualx.core.port.FileStore;
import com.dualx.core.port.FileUrlSigner;
import com.dualx.core.plan.PlanService;
import com.dualx.core.replay.ReplayService;
import com.dualx.core.state.SituationEnvelopeFactory;
import com.dualx.core.state.SituationQuery;
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

    /**
     * 想定路由的 {@code {planId}} 取值，理由同 {@link #MAP_ID}。
     *
     * <p>想定 CRUD 接线后，控制器把「拼不出来的 planId」折叠成 {@code PLAN_NOT_FOUND}（404）——
     * 与本测试「非 404」的判据正好撞车（占位串 {@code p1} 会让已注册路由看起来像没注册）。</p>
     */
    private static final String PLAN_ID = "018f4a2b-0000-7000-8000-000000000002";

    /**
     * 复盘路由的 {@code {replayId}} 取值，理由同 {@link #MAP_ID}。
     *
     * <p>R1 接线后，控制器把「拼不出来的 replayId」折叠成 {@code REPLAY_NOT_FOUND}（404）——
     * 与「路由没注册」的 404 撞车（占位串 {@code r1} 会让已注册路由看起来像没注册）。</p>
     */
    private static final String REPLAY_ID = "018f4a2b-0000-7000-8000-000000000003";

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
        // UWB 中心基准校准状态机已接上三端点（CAL-2）。它在本测试里同样给抛占位异常的替身：
        // 放行的 start / status / clear 因此稳定返回 501。真状态机（采样、静止校验、写回
        // uwb_meta、链路未开 409）由 UwbCalibrationServiceTest 与 scds-app 的端到端覆盖。
        UwbCalibrationService uwbCalibrationService = Mockito.mock(UwbCalibrationService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 底图详情要签 imageUrl，但本测试里底图相关调用一律在替身处就抛占位异常，签名端口不会真被用到；
        // 给个同样会抛的替身，是为了让「装配缺件」以断言失败的形式暴露，而不是静默返回 null。
        FileUrlSigner fileUrlSigner = Mockito.mock(FileUrlSigner.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 受保护文件的签发与落盘都在 scds-core 的适配器里（要真实文件系统与 scds.files.* 配置）。
        // 两个替身都抛占位异常：/api/v1/files 因此稳定返回 501 而不是真去验签——
        // 本测试只问「这条路由注册了没有」，验签与按格式回 Content-Type 由文件域自己的测试覆盖。
        FileStore fileStore = Mockito.mock(FileStore.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 想定 / 编成 / 模板导入已接核心 PlanService：同上，替身一律抛占位异常，
        // 使已接线的 9 个想定端点在本测试里同样表现为 501。真实链路（三条编成校验、
        // 想定名口径、整文件导入的事务边界、演习中 409）由 PlanServiceIT / PlanImportIT
        // 与 scds-app 的 PlanHttpIT 覆盖。
        PlanService planService = Mockito.mock(PlanService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 链路端点已接线到领域端口 DeviceLinkAdmin（真实现是 scds-gateway 的真链路或仿真适配器）。
        // 本测试不启 Spring 上下文，也不碰硬件：替身一律抛占位异常，使链路端点在这里稳定表现为
        // 400（请求体校验）/501（端口被调到），而不是 404。真实链路开关、409 与 12001 硬约束由
        // RealDeviceLinkAdminTest / LinkRegistryTest 与 scds-app 的 HwLinkHttpIT 覆盖。
        DeviceLinkAdmin deviceLinkAdmin = Mockito.mock(DeviceLinkAdmin.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 演习生命周期已接核心 ExerciseLifecycleService：同上，替身一律抛占位异常，
        // 使已接线的读/开演/停演端点在本测试里同样表现为 400（请求体缺失）/501。
        // 真实链路（单实例保护、编成校验、停演冻结口径）由 ExerciseLifecycleServiceIT 与
        // scds-app 的 ExerciseLifecycleHttpIT 覆盖。
        ExerciseLifecycleService lifecycle = Mockito.mock(ExerciseLifecycleService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 当前演习的 onlineCount 要读态势读侧：同样给抛占位异常的替身。真装配
        // （SituationStateService 及其一串协作者）在 scds-app 的 SituationStateConfig。
        SituationQuery situationQuery = Mockito.mock(SituationQuery.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 态势四个 Poll 端点已接 core 的线载荷组装器（它内部走 SituationQuery 原语）：
        // 替身一律抛占位异常，使四个端点在本测试里同样表现为 501（只证明路由注册）。
        // 组装口径（全量/增量/超窗 409/编成外马甲 404）由 SituationEnvelopeFactoryTest 证明，
        // 端到端由 scds-app 的态势 HTTP 测试覆盖。
        SituationEnvelopeFactory situationEnvelopeFactory = Mockito.mock(SituationEnvelopeFactory.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 指令端点（8 条已全部落地）已接 core 的编排服务 CommandService（要四个仓储、硬件下行
        // 端口、地图台账与态势写入侧，各自都要数据源），本测试不启 Spring 上下文。替身一律抛占位异常；
        // 已落地的端点在建线前就会先按令牌/参数失败（401 / 400），仍只证明路由注册——本测试的判据是「非 404」。
        // 编排口径（先发字节再落 SENT、链路未开即未入队、无演习 409、编成外 404、广播 vest_num=0、
        // 手动置位不发字节且正式演习 403）由 scds-app 的 CommandHttpIT 端到端证明。
        CommandService commandService = Mockito.mock(CommandService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        // 复盘读侧（R1）已接核心 ReplayService：同一取向，替身一律抛占位异常，
        // 使 listReplays / getReplay 在本测试里仍表现为 501（只证明路由注册）。
        // 真实链路（进行中不算复盘、404 判据、分页与模糊搜索口径）由 ReplayServiceIT 覆盖。
        ReplayService replayService = Mockito.mock(ReplayService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class) {
                return invocation.callRealMethod();
            }
            throw new NotImplementedException(invocation.getMethod().getName());
        });
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountAuthController(authService),
                        new AuthController(authService, deviceAuthService, vestLedgerService, situationQuery),
                        new ExerciseController(planService, lifecycle, situationQuery),
                        new CommandController(commandService),
                        new SituationController(situationEnvelopeFactory),
                        new DeviceController(vestLedgerService, anchorLedgerService, deviceLinkAdmin, situationQuery),
                        // standalone 不装配 Spring 上下文，ObjectMapper 只能自备：本测试里
                        // 底图相关的 JSON 进出都会被替身的占位异常截断，用默认配置即可。
                        new MapController(mapLedgerService, uwbCalibrationService, new ObjectMapper(), fileUrlSigner),
                        new FileController(fileStore, fileUrlSigner),
                        new ReplayController(replayService))
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

    /** 契约 {@code docs/api/paths/*.yaml} 的全部 70 条 REST 路由。 */
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
                route("GET", "/api/v1/plans/" + PLAN_ID),
                route("PUT", "/api/v1/plans/" + PLAN_ID),
                route("DELETE", "/api/v1/plans/" + PLAN_ID),
                route("GET", "/api/v1/plans/" + PLAN_ID + "/roster"),
                route("PUT", "/api/v1/plans/" + PLAN_ID + "/roster"),
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
                route("GET", "/api/v1/replays/" + REPLAY_ID),
                route("GET", "/api/v1/replays/" + REPLAY_ID + "/timeline"),
                route("GET", "/api/v1/replays/" + REPLAY_ID + "/statistics"),
                route("POST", "/api/v1/replays/" + REPLAY_ID + "/exports"),
                route("GET", "/api/v1/replays/" + REPLAY_ID + "/exports/t1"),
                route("GET", "/api/v1/replays/" + REPLAY_ID + "/exports/t1/download"),
                // ---- file（1）----
                // 带上三个查询参数而不是裸路径：裸路径会因缺参在参数解析阶段就 400，
                // 那样只证明了「有条路由匹配上了」。带参则真的进到控制器里——签名端口的替身抛占位异常，
                // 稳定得到 501，既证明路由注册，也证明它真的接到了签名端口。
                route("GET", "/api/v1/files?path=maps/m1/a.png&exp=1&signature=x")
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
        // 用仍是真占位的复盘端点：R1 接线 listReplays / getReplay，R2 接线 timeline，R3 又接线了
        // statistics，故这里改取导出任务查询端点——它由控制器直接抛占位异常，与接线情况无关。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/replays/" + REPLAY_ID + "/exports/t1")
                        .header(TraceIdFilter.HEADER, traceId))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.status").value(501))
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/replays/" + REPLAY_ID + "/exports/t1"))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
