package com.dualx.api.error;

import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务异常出口：core 抛 {@code BusinessException}，接口层折成契约形状的 {@code Problem}。
 *
 * <p>用 {@code standaloneSetup} 不启 Spring 上下文：这里要验的是「码 → 状态 + 头 + 体」的折换，
 * 与数据源、Security 过滤链无关。</p>
 */
class ApiExceptionHandlerBusinessTest {

    private static MockMvc mockMvc;

    @BeforeAll
    static void setUpMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    /** 只负责抛出各种业务失败，不带任何业务逻辑。 */
    @RestController
    static class ThrowingController {

        @GetMapping("/boom/bad-credentials")
        void badCredentials() {
            throw new BusinessException(ApiErrorCode.AUTH_BAD_CREDENTIALS, "账号或口令错误");
        }

        @GetMapping("/boom/expired")
        void expired() {
            throw new BusinessException(ApiErrorCode.AUTH_TOKEN_EXPIRED, "令牌已过期");
        }

        @GetMapping("/boom/device-not-bound")
        void deviceNotBound() {
            throw new BusinessException(ApiErrorCode.AUTH_DEVICE_NOT_BOUND, "马甲未绑定");
        }

        @GetMapping("/boom/forbidden")
        void forbidden() {
            throw new BusinessException(ApiErrorCode.AUTH_FORBIDDEN, "无权访问该资源");
        }

        @GetMapping("/boom/rate-limited")
        void rateLimited() {
            throw BusinessException.rateLimited(Duration.ofSeconds(123));
        }

        @GetMapping("/boom/vest-not-found")
        void vestNotFound() {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_NOT_FOUND, "马甲不存在");
        }

        @GetMapping("/boom/vest-num-conflict")
        void vestNumConflict() {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_NUM_CONFLICT, "马甲编号已存在");
        }

        @GetMapping("/boom/vest-already-bound")
        void vestAlreadyBound() {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_ALREADY_BOUND, "该马甲已绑定");
        }

        @GetMapping("/boom/vest-not-bound")
        void vestNotBound() {
            throw new BusinessException(ApiErrorCode.DEVICE_VEST_NOT_BOUND, "该马甲尚未绑定");
        }

        @GetMapping("/boom/exercise-running")
        void exerciseRunning() {
            throw new BusinessException(ApiErrorCode.EXERCISE_ALREADY_RUNNING, "演习进行中");
        }

        @GetMapping("/boom/exercise-not-running")
        void exerciseNotRunning() {
            throw new BusinessException(ApiErrorCode.EXERCISE_NOT_RUNNING, "当前没有进行中的演习");
        }

        @GetMapping("/boom/exercise-plan-missing")
        void exercisePlanMissing() {
            throw new BusinessException(ApiErrorCode.EXERCISE_PLAN_NOT_FOUND, "想定不存在");
        }

        @GetMapping("/boom/exercise-roster-empty")
        void exerciseRosterEmpty() {
            throw new BusinessException(ApiErrorCode.EXERCISE_ROSTER_EMPTY, "想定编成为空，无法开演");
        }

        @GetMapping("/boom/link-already-open")
        void linkAlreadyOpen() {
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_ALREADY_OPEN, "链路已开启：SERIAL_VEST");
        }

        @GetMapping("/boom/link-serial-busy")
        void linkSerialBusy() {
            throw new BusinessException(ApiErrorCode.DEVICE_LINK_SERIAL_BUSY, "串口已被 UWB_SERIAL 占用：COM7");
        }

        @GetMapping("/boom/map-not-found")
        void mapNotFound() {
            throw new BusinessException(ApiErrorCode.MAP_NOT_FOUND, "底图不存在");
        }

        @GetMapping("/boom/map-in-use")
        void mapInUse() {
            throw new BusinessException(ApiErrorCode.MAP_IN_USE, "底图已被想定引用，不能删除");
        }

        @GetMapping("/boom/instance-not-ready")
        void instanceNotReady() {
            throw new BusinessException(ApiErrorCode.INSTANCE_NOT_READY, "本实例尚未完成启动自愈，暂不可开演");
        }

        @GetMapping("/boom/revision-too-old")
        void revisionTooOld() {
            throw new BusinessException(ApiErrorCode.SITUATION_REVISION_TOO_OLD,
                    "持有的 revision 已超出服务端保留窗口，请改为全量拉取");
        }

        @GetMapping("/boom/command-invalid-weapon")
        void commandInvalidWeapon() {
            throw new BusinessException(ApiErrorCode.COMMAND_INVALID_WEAPON_SLOT, "武器槽位非法");
        }

        @GetMapping("/boom/command-vest-offline")
        void commandVestOffline() {
            throw new BusinessException(ApiErrorCode.COMMAND_VEST_OFFLINE, "马甲离线");
        }

        @GetMapping("/boom/command-vest-dead")
        void commandVestDead() {
            throw new BusinessException(ApiErrorCode.COMMAND_VEST_DEAD, "马甲已阵亡");
        }

        @GetMapping("/boom/command-queue-full")
        void commandQueueFull() {
            throw new BusinessException(ApiErrorCode.COMMAND_QUEUE_FULL, "指令队列已满");
        }

        @GetMapping("/boom/unmapped")
        void unmapped() {
            throw new BusinessException(ApiErrorCode.REPLAY_EXPORT_FAILED, "复盘导出失败");
        }
    }

    @Test
    @DisplayName("认证类业务失败 → 401，且形状与非业务失败完全一致")
    void credentialFailuresAreUnauthorizedProblems() throws Exception {
        String traceId = "biz-trace-bad-credentials";
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/bad-credentials").header(TraceIdFilter.HEADER, traceId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(TraceIdFilter.HEADER, traceId))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.title").value("账号或口令错误"))
                .andExpect(jsonPath("$.instance").value("/boom/bad-credentials"))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("令牌过期 / 设备未绑定同样是 401，但码各不相同——前端据此分流引导")
    void otherAuthFailuresKeepTheirOwnCode() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.traceId").value(not(emptyString())));

        mockMvc.perform(MockMvcRequestBuilders.get("/boom/device-not-bound"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_DEVICE_NOT_BOUND"));
    }

    @Test
    @DisplayName("越权 → 403（与 401 严格分工：令牌有效、角色不允许）")
    void forbiddenIs403Not401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("限流 → 429 且带整秒 Retry-After")
    void rateLimitedCarriesRetryAfterInWholeSeconds() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "123"));
    }

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(delimiter = '|', value = {
            "/boom/vest-not-found    | 404 | DEVICE_VEST_NOT_FOUND",
            "/boom/vest-num-conflict | 409 | DEVICE_VEST_NUM_CONFLICT",
            "/boom/vest-already-bound| 409 | DEVICE_VEST_ALREADY_BOUND",
            "/boom/vest-not-bound    | 409 | DEVICE_VEST_NOT_BOUND",
            "/boom/exercise-running  | 409 | EXERCISE_ALREADY_RUNNING",
            // 演习块四个码同前缀、三种状态：开演引用的想定不存在是 404、编成为空是 400，
            // 开演/停演与「当前是否在跑」冲突是 409——逐项登记而不是按 EXERCISE_ 前缀猜一个状态。
            "/boom/exercise-not-running   | 409 | EXERCISE_NOT_RUNNING",
            "/boom/exercise-plan-missing  | 404 | EXERCISE_PLAN_NOT_FOUND",
            "/boom/exercise-roster-empty   | 400 | EXERCISE_ROSTER_EMPTY",
            // 链路两码同为 409、不同码：契约要求两种冲突可区分（「谁占着」），故逐项登记而非折成一个。
            "/boom/link-already-open | 409 | DEVICE_LINK_ALREADY_OPEN",
            "/boom/link-serial-busy  | 409 | DEVICE_LINK_SERIAL_BUSY",
            "/boom/map-not-found     | 404 | MAP_NOT_FOUND",
            "/boom/map-in-use        | 409 | MAP_IN_USE",
            // 态势：revision 超窗（M4 PUSH-MODEL）。契约 getSituation 明确写 409——请求本身合法，
            // 只是与当前服务端状态冲突；客户端处置是「丢弃本地缓存改全量拉取」，不是「改参数重试」。
            "/boom/revision-too-old  | 409 | SITUATION_REVISION_TOO_OLD",
            // 指挥指令（M3 COMMAND）：契约只对武器槽位写明了 400；离线/阵亡取 409、队列满取 503
            // （容量问题，客户端动作是「稍后重试」，与 500 的「翻日志」严格分工）。
            "/boom/command-invalid-weapon | 400 | COMMAND_INVALID_WEAPON_SLOT",
            "/boom/command-vest-offline   | 409 | COMMAND_VEST_OFFLINE",
            "/boom/command-vest-dead      | 409 | COMMAND_VEST_DEAD",
            "/boom/command-queue-full     | 503 | COMMAND_QUEUE_FULL",
    })
    @DisplayName("台账域、演习域、链路域、地图域、态势与指挥域错误码逐项按契约登记状态，不按前缀猜（DEVICE_* / EXERCISE_* / COMMAND_* 里既有 400 又有 404/409）")
    void ledgerCodesMapToTheirContractStatus(String path, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(path.trim()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    @DisplayName("实例尚未就绪 → 503：与 INTERNAL_ERROR 的 500 分工，客户端动作是「稍后重试」而不是翻日志")
    void instanceNotReadyIs503Not500() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/instance-not-ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_READY"))
                .andExpect(jsonPath("$.status").value(503))
                // 门禁窗口有多长不可预知（取决于残留场次的收尾耗时），不给 Retry-After：
                // 编一个秒数反而会让客户端按错误的节奏重试。
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("未登记状态的错误码响亮地失败：500 并在响应里保留原码")
    void unmappedCodeFailsLoudlyInsteadOfGuessingAnHttpStatus() throws Exception {
        // 这条是刻意的绊线：状态映射表刻意不按前缀猜（同一前缀里既有 404 又有 409）。
        // 台账域（M2 LEDGER）、演习块（M3 LIFE）、态势（M4）与指挥指令（M3 COMMAND）登记完后，
        // 绊线换到尚未落服务的 REPLAY_* 上；将来为 REPLAY_EXPORT_FAILED 登记了状态，
        // 本用例会失败——那一刻请一并换成下一个未登记的码，而不是把默认分支改回「猜一个
        // 看起来合理的 4xx」。
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/unmapped"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("REPLAY_EXPORT_FAILED"));
    }
}
