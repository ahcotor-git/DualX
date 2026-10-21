package com.dualx.api;

import com.dualx.api.auth.AuthController;
import com.dualx.api.error.ApiExceptionHandler;
import com.dualx.api.support.TraceIdFilter;
import com.dualx.core.auth.AuthService;
import com.dualx.core.auth.DeviceAuthService;
import com.dualx.core.error.ApiErrorCode;
import com.dualx.core.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Boot MVC advice coexistence; security is deliberately outside this test's scope.
 * Multipart parser tests use TCP on a loopback ephemeral port.
 */
@SpringBootTest(classes = MvcProblemIT.MvcApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.mvc.problemdetails.enabled=true",
        "spring.servlet.multipart.max-file-size=1KB",
        "spring.servlet.multipart.max-request-size=4KB"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ActiveProfiles("err01-test")
class MvcProblemIT {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
            OAuth2ResourceServerAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class})
    @Import({ApiExceptionHandler.class, TraceIdFilter.class, AuthController.class,
            Endpoints.class, LegacyValidatedEndpoints.class})
    static class MvcApplication {

        /** 本测试只验证 MVC 错误出口，不建立数据库上下文：账号服务用替身，方法不会被调到。 */
        @Bean
        AuthService authService() {
            return Mockito.mock(AuthService.class);
        }

        /**
         * 设备登录已接核心服务。本测试只验证 MVC 出口，故让替身固定抛一条业务失败——
         * 这样「已接线的端点」与 {@code /err01/business} 走的是同一条 problem+json 通道。
         */
        @Bean
        DeviceAuthService deviceAuthService() {
            DeviceAuthService service = Mockito.mock(DeviceAuthService.class);
            Mockito.when(service.login(Mockito.anyInt(), Mockito.anyString(), Mockito.anyString()))
                    .thenThrow(new BusinessException(ApiErrorCode.AUTH_DEVICE_NOT_BOUND,
                            "马甲编号或绑定密钥不正确"));
            return service;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate http;

    @Test
    void bootDefaultAdviceCoexistsWithoutDatabase() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansWithAnnotation(org.springframework.web.bind.annotation.ControllerAdvice.class)
                .values()).anyMatch(bean -> bean.getClass().getSimpleName().equals("ProblemDetailsExceptionHandler"));
    }

    @Test
    void realDeviceLoginDtoValidation() throws Exception {
        var body = problem(post("/api/v1/auth/device/login")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("vestNum", "secret");
    }

    @Test
    void malformedJsonHasSafeBodyError() throws Exception {
        var body = problem(post("/api/v1/auth/device/login")
                .contentType(MediaType.APPLICATION_JSON).content("{broken-secret"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("$");
        assertThat(body.toString()).doesNotContain("broken-secret", "JsonParseException");
    }

    @Test
    void jsonFieldTypeMismatchHasFieldError() throws Exception {
        var body = problem(post("/api/v1/auth/device/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"vestNum\":\"invalid\",\"secret\":\"sensitive-value\"}"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("vestNum");
        assertThat(body.toString()).doesNotContain("sensitive-value");
    }

    @Test
    void missingRequestBody() throws Exception {
        var body = problem(post("/api/v1/auth/device/login")
                .contentType(MediaType.APPLICATION_JSON), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("$");
    }

    @Test
    void missingQueryParameter() throws Exception {
        var body = problem(get("/err01/query"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("count");
    }

    @Test
    void incorrectQueryType() throws Exception {
        var body = problem(get("/err01/query").param("count", "wrong"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("count");
    }

    @Test
    void incorrectPathType() throws Exception {
        var body = problem(get("/err01/path/wrong"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("id");
    }

    @Test
    void mvcMethodParameterValidation() throws Exception {
        var body = problem(get("/err01/query").param("count", "0"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("count");
    }

    @Test
    void mvcMethodReturnValidationIsServerError() throws Exception {
        problem(get("/err01/invalid-return"), 500, "INTERNAL_ERROR");
    }

    @Test
    void legacyValidatedParameter() throws Exception {
        var body = problem(get("/err01/legacy").param("count", "0"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).anyMatch(field -> field.endsWith("count"));
    }

    @Test
    void legacyValidatedReturnIsServerError() throws Exception {
        problem(get("/err01/legacy-return"), 500, "INTERNAL_ERROR");
    }

    @Test
    void modelAttributeBindingErrors() throws Exception {
        var body = problem(get("/err01/model").param("count", "sensitive-rejected-value"),
                400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("count");
        assertThat(body.toString()).doesNotContain("sensitive-rejected-value", "java.lang");
    }

    @Test
    void absentRoute() throws Exception {
        problem(get("/err01/absent"), 404, "ROUTE_NOT_FOUND");
    }

    @Test
    void unsupportedMethodRetainsAllow() throws Exception {
        MvcResult result = performProblem(post("/err01/query"), 405, "METHOD_NOT_ALLOWED");
        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET");
    }

    @Test
    void unsupportedContentType() throws Exception {
        problem(post("/err01/body").contentType(MediaType.TEXT_PLAIN).content("text"),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unacceptableResponseType() throws Exception {
        problem(get("/err01/json").accept(MediaType.APPLICATION_XML), 406, "NOT_ACCEPTABLE");
    }

    @Test
    void missingMultipartField() throws Exception {
        var body = problem(multipart("/err01/upload"), 400, "VALIDATION_FAILED");
        assertThat(body.path("errors").findValuesAsText("field")).contains("file");
    }

    @Test
    void actualContainerMultipartLimit() throws Exception {
        uploadLimit(2048);
    }

    @Test
    void actualContainerRequestLimit() throws Exception {
        uploadLimit(8192);
    }

    private void uploadLimit(int size) throws Exception {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource(new byte[size]) {
            @Override public String getFilename() { return "limit.bin"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(TraceIdFilter.HEADER, "err01-real-upload");
        var response = http.postForEntity("/err01/upload", new HttpEntity<>(parts, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isNotNull()
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        var body = mapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(413);
        assertThat(body.path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(body.path("instance").asText()).isEqualTo("/err01/upload");
        assertThat(body.path("traceId").asText()).isEqualTo("err01-real-upload");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo("err01-real-upload");
        assertThat(body.has("errors")).isFalse();
    }

    @Test
    void actualMalformedMultipart() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(TraceIdFilter.HEADER, "err01-malformed-upload");
        var response = http.postForEntity("/err01/upload",
                new HttpEntity<>("sensitive-multipart-value", headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isNotNull()
                .matches(type -> type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        var body = mapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.path("instance").asText()).isEqualTo("/err01/upload");
        assertThat(body.path("traceId").asText()).isEqualTo("err01-malformed-upload");
        assertThat(response.getHeaders().getFirst(TraceIdFilter.HEADER)).isEqualTo("err01-malformed-upload");
        assertThat(body.path("errors").findValuesAsText("field")).contains("$");
        assertThat(body.toString()).doesNotContain("sensitive-multipart-value", "Exception");
    }

    @Test
    void generatedTraceIdMatchesResponseHeader() throws Exception {
        var result = mvc.perform(get("/err01/query"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
                .path("traceId").asText()).isEqualTo(traceId);
    }

    @Test
    void validMultipartStillWorks() throws Exception {
        mvc.perform(multipart("/err01/upload").file("file", new byte[] {1}))
                .andExpect(status().isOk());
    }

    @Test
    void businessRetryAfterUnchanged() throws Exception {
        MvcResult result = performProblem(get("/err01/rate"), 429, "RATE_LIMITED");
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("123");
    }

    @Test
    void businessUnregisteredMappingStillFailsLoudly() throws Exception {
        // 绊线：台账域（DEVICE_VEST_*、EXERCISE_ALREADY_RUNNING）已登记状态后，
        // 样例换到尚未落服务的 COMMAND_QUEUE_FULL。为它登记状态时本用例会失败，
        // 那一刻请换成下一个未登记的码，而不是把默认分支改成「猜一个 4xx」。
        problem(get("/err01/business"), 500, "COMMAND_QUEUE_FULL");
    }

    @Test
    void wiredDeviceLoginSurfacesBusinessFailureAsProblem() throws Exception {
        // 已接核心 DeviceAuthService 的端点：业务失败（此处由替身固定抛出）与 /err01/business
        // 走同一条 problem+json 出口，不再是占位 501。
        problem(post("/api/v1/auth/device/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"vestNum\":7,\"secret\":\"test\"}"), 401, "AUTH_DEVICE_NOT_BOUND");
    }

    @Test
    void unexpectedExceptionDoesNotLeakDetails() throws Exception {
        var body = problem(get("/err01/unexpected"), 500, "INTERNAL_ERROR");
        assertThat(body.toString()).doesNotContain("secret-server-detail", "IllegalStateException");
    }

    private com.fasterxml.jackson.databind.JsonNode problem(MockHttpServletRequestBuilder request,
                                                            int expected, String code) throws Exception {
        return mapper.readTree(performProblem(request, expected, code).getResponse().getContentAsByteArray());
    }

    private MvcResult performProblem(MockHttpServletRequestBuilder request, int expected, String code)
            throws Exception {
        MvcResult result = mvc.perform(request.header(TraceIdFilter.HEADER, "err01-regression"))
                .andExpect(status().is(expected))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").value("err01-regression"))
                .andReturn();
        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER)).isEqualTo("err01-regression");
        var body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.path("instance").asText()).isEqualTo(result.getRequest().getRequestURI());
        if ("VALIDATION_FAILED".equals(code)) {
            assertThat(body.path("errors").isArray()).isTrue();
            assertThat(body.path("errors").size()).isPositive();
            body.path("errors").forEach(error -> {
                assertThat(error.path("field").asText()).isNotBlank();
                assertThat(error.path("message").asText()).isNotBlank();
            });
        } else {
            assertThat(body.has("errors")).isFalse();
        }
        return result;
    }

    public record Body(@NotBlank String name) { }
    public static class Query {
        private Integer count;
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
    }

    @RestController
    public static class Endpoints {
        @GetMapping("/err01/query")
        public int query(@RequestParam("count") @Min(1) int count) { return count; }
        @GetMapping("/err01/path/{id}")
        public int path(@PathVariable("id") int id) { return id; }
        @GetMapping("/err01/model")
        public Integer model(@ModelAttribute Query query) { return query.getCount(); }
        @GetMapping("/err01/invalid-return")
        @NotNull
        public String invalidReturn() { return null; }
        @PostMapping(value = "/err01/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Body body(@RequestBody @Valid Body body) { return body; }
        @GetMapping(value = "/err01/json", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> json() { return Map.of("ok", "ok"); }
        @PostMapping(value = "/err01/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public long upload(@RequestPart("file") MultipartFile file) { return file.getSize(); }
        @GetMapping("/err01/rate")
        public void rate() {
            throw new BusinessException(ApiErrorCode.RATE_LIMITED, "rate", Duration.ofSeconds(123));
        }
        @GetMapping("/err01/business")
        public void business() { throw new BusinessException(ApiErrorCode.COMMAND_QUEUE_FULL, "queue full"); }
        @GetMapping("/err01/unexpected")
        public void unexpected() { throw new IllegalStateException("secret-server-detail"); }
    }

    @RestController
    @Validated
    public static class LegacyValidatedEndpoints {
        @GetMapping("/err01/legacy")
        public int legacy(@RequestParam("count") @Min(1) int count) { return count; }
        @GetMapping("/err01/legacy-return")
        @NotNull
        public String invalidReturn() { return null; }
    }
}
