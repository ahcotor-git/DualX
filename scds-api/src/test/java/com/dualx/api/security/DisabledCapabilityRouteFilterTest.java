package com.dualx.api.security;

import com.dualx.api.support.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 能力关闭端点的 404 出口：精确路径 + **按实际关闭的能力分表** + 排在 Bearer 验证之前（设计 03b §5）。
 *
 * <p>本测试只覆盖过滤器自身的判定；「无效 Bearer 在真实安全链上仍得 404」由
 * {@code ApplicationStartupIT} 用真 Tomcat + 真 Security 验证——因为「无效 Bearer 会先被
 * 401 拦下」正是本过滤器要解决的问题，那是过滤链顺序的产物，单元测试看不到顺序。</p>
 */
class DisabledCapabilityRouteFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void accountDisabledTurnsPreciseAccountPathsIntoRouteNotFound(String path) throws Exception {
        assertRouteNotFound(filter(false, true), path);
    }

    @Test
    void deviceDisabledTurnsDeviceLoginIntoRouteNotFound() throws Exception {
        assertRouteNotFound(filter(true, false), "/api/v1/auth/device/login");
    }

    @Test
    void bothDisabledTurnEveryIssuancePathIntoRouteNotFound() throws Exception {
        DisabledCapabilityRouteFilter filter = filter(false, false);
        assertRouteNotFound(filter, "/api/v1/auth/login");
        assertRouteNotFound(filter, "/api/v1/auth/refresh");
        assertRouteNotFound(filter, "/api/v1/auth/logout");
        assertRouteNotFound(filter, "/api/v1/auth/device/login");
    }

    @Test
    void accountDisabledLeavesDeviceLoginAlone() throws Exception {
        assertThat(passesThrough(filter(false, true), "POST", "/api/v1/auth/device/login")).isTrue();
    }

    @Test
    void deviceDisabledLeavesAccountPathsAlone() throws Exception {
        DisabledCapabilityRouteFilter filter = filter(true, false);
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/login")).isTrue();
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/refresh")).isTrue();
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/logout")).isTrue();
    }

    @Test
    void bothEnabledLeaveIssuanceRoutesOnTheChain() throws Exception {
        DisabledCapabilityRouteFilter filter = filter(true, true);
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/login")).isTrue();
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/device/login")).isTrue();
    }

    @Test
    void invalidBearerHeaderDoesNotEscapeTheGate() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.addHeader("Authorization", "Bearer not-a-real-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter(false, true).doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("带无效 Bearer 也必须停在 404，而不是落到 401").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(MAPPER.readTree(response.getContentAsString()).path("code").asText())
                .isEqualTo("ROUTE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "OPTIONS"})
    void nonPostMethodOnTheSamePathIsNotSwallowed(String method) throws Exception {
        DisabledCapabilityRouteFilter filter = filter(false, false);
        assertThat(passesThrough(filter, method, "/api/v1/auth/login")).isTrue();
        assertThat(passesThrough(filter, method, "/api/v1/auth/device/login")).isTrue();
    }

    @Test
    void otherPathsKeepTheirNormalSecurityFlow() throws Exception {
        DisabledCapabilityRouteFilter filter = filter(false, false);
        assertThat(passesThrough(filter, "POST", "/api/v1/devices/vests")).isTrue();
        assertThat(passesThrough(filter, "GET", "/api/v1/auth/me")).isTrue();
        assertThat(passesThrough(filter, "POST", "/api/v1/auth/loginX")).isTrue();
    }

    private static void assertRouteNotFound(DisabledCapabilityRouteFilter filter, String path) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, "trace-1");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("请求不应继续往后走").isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(404);
        assertThat(body.path("code").asText()).isEqualTo("ROUTE_NOT_FOUND");
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("traceId").asText()).isEqualTo("trace-1");
    }

    private static boolean passesThrough(DisabledCapabilityRouteFilter filter, String method, String path)
            throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    private static DisabledCapabilityRouteFilter filter(boolean accountEnabled, boolean deviceEnabled) {
        AuthJwtProperties account = new AuthJwtProperties(
                null, accountEnabled, null, null, null, null, null, null, null, null);
        // 设备启用时 DeviceJwtProperties 的紧凑构造器要求 issuer 与密钥来源非空；本测试只判
        // 「开关是否为 true」，故给可辨识的占位文本即可，不涉及真实 PEM 解析（§10.1 边界）。
        DeviceJwtProperties device = deviceEnabled
                ? new DeviceJwtProperties("scds-device-test", true, "dummy-pem", null, "dummy-pem", null, null)
                : new DeviceJwtProperties(null, false, null, null, null, null, null);
        return new DisabledCapabilityRouteFilter(account, device, MAPPER);
    }
}
