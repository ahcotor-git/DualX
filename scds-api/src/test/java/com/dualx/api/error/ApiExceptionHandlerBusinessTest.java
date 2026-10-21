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

        @GetMapping("/boom/unmapped")
        void unmapped() {
            throw new BusinessException(ApiErrorCode.COMMAND_QUEUE_FULL, "指令队列已满");
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
    })
    @DisplayName("台账域错误码逐项按契约登记状态，不按前缀猜（DEVICE_* 里既有 404 又有 409）")
    void ledgerCodesMapToTheirContractStatus(String path, int expectedStatus, String expectedCode) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(path.trim()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    @Test
    @DisplayName("未登记状态的错误码响亮地失败：500 并在响应里保留原码")
    void unmappedCodeFailsLoudlyInsteadOfGuessingAnHttpStatus() throws Exception {
        // 这条是刻意的绊线：状态映射表刻意不按前缀猜（同一前缀里既有 404 又有 409）。
        // 台账域（M2 LEDGER）登记完 DEVICE_VEST_* 与 EXERCISE_ALREADY_RUNNING 后，
        // 绊线换到尚未落服务的 COMMAND_* 上；将来为 COMMAND_QUEUE_FULL 登记了状态，
        // 本用例会失败——那一刻请一并换成下一个未登记的码，而不是把默认分支改回「猜一个
        // 看起来合理的 4xx」。
        mockMvc.perform(MockMvcRequestBuilders.get("/boom/unmapped"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMAND_QUEUE_FULL"));
    }
}
