package com.dualx.api.security;

import com.dualx.core.error.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 能力关闭端点的 404 出口，排在 Bearer 验证**之前**（设计 03b §5 / §8 工作包 5）。
 *
 * <p><b>为什么不能只靠「路由缺席」。</b>条件注册的 controller 确实让路由不存在，但
 * dispatcher 的 404 只有请求**抵达** dispatcher 才看得见。带 <b>无效 Bearer</b> 的请求会
 * 先在 {@code BearerTokenAuthenticationFilter} 上验签失败，由入口点写成 401——
 * {@code permitAll} 拦不住它，因为放行只作用于更靠后的 {@code AuthorizationFilter}。
 * 于是同一个「本实例没有这个能力」的事实，无令牌时是 404、带无效令牌时却成了 401，
 * 客户端既无法据此换 {@code baseUrl}，也分不清「令牌不成立」与「能力缺席」。</p>
 *
 * <p><b>落法。</b>只对**实际被关闭能力的精确 operation 路径**（方法 + 路径）固定写
 * {@code ROUTE_NOT_FOUND}；不注册占位 controller，也不放行整个 {@code /api/v1/auth/**}。
 * 排在 Spring Security 过滤链之前（安全链 order = -100，本过滤器取更高优先级，
 * 但仍在 {@link com.dualx.api.support.TraceIdFilter} 之后，故 404 也带 traceId），
 * 于是「无令牌 / 合法令牌 / 无效令牌」三种请求得到同一个 404。其余受保护路径完全不受影响，
 * 仍走正常 401/403。</p>
 *
 * <p><b>与能力开关的关系（03b §5 第 135 行）。</b>账号签发与设备签发是两个**独立开关**
 * （{@code scds.auth.jwt.account.signing-enabled} / {@code scds.auth.jwt.device.signing-enabled}），
 * 因此本过滤器**按实际关闭的能力**分表：账号关闭时缺席 {@code login}/{@code refresh}/{@code logout}，
 * 设备关闭时缺席 {@code device/login}。两张表的判定必须与其对应控制器的注册条件一致
 * （{@code AccountAuthController} 账号缺省为开、{@code DeviceAuthController} 设备缺省为关），
 * 否则会出现「端点注册了却签不出」的 500——§5 第 137 行明令不得把这种配置错误当成能力关闭响应。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DisabledCapabilityRouteFilter extends OncePerRequestFilter {

    /**
     * 账号签发能力关闭（{@code scds.auth.jwt.account.signing-enabled=false}）时缺席的精确 operation 路径。
     *
     * <p>只有**方法 + 路径**都吻合的条目才登记：同名路径上的其他方法本就不是契约里的 operation，
     * 不该被这条 404 出口顺带放行或改变其既有 401/403。{@code logout} 属此表——它虽始终要求账号令牌、
     * 不进免鉴权白名单，但账号控制器关闭时其路由同样缺席，故仍需这条 404 出口。</p>
     */
    private static final Set<String> ACCOUNT_DISABLED_OPERATIONS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout");

    /** 设备签发能力关闭（{@code scds.auth.jwt.device.signing-enabled=false}，**缺省值**）时缺席的精确路径。 */
    private static final Set<String> DEVICE_DISABLED_OPERATIONS = Set.of(
            "/api/v1/auth/device/login");

    private final AuthJwtProperties accountProperties;
    private final DeviceJwtProperties deviceProperties;
    private final ObjectMapper objectMapper;

    public DisabledCapabilityRouteFilter(AuthJwtProperties accountProperties,
                                         DeviceJwtProperties deviceProperties,
                                         ObjectMapper objectMapper) {
        this.accountProperties = accountProperties;
        this.deviceProperties = deviceProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (isDisabledOperation(request)) {
            // 与 dispatcher 的 404 保持同一形状（code/instance/traceId 一致，title 取同一
            // reason phrase），客户端不必、也无法区分这条 404 是"路由没注册"还是"能力被关"。
            ApiProblemWriter.write(objectMapper, request, response, HttpStatus.NOT_FOUND,
                    ApiErrorCode.ROUTE_NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase(), null);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isDisabledOperation(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return false;
        }
        // 安全链里的 requestMatchers 以 context path 为界；这里同样剥掉前缀再比对，
        // 否则一旦部署在非根 context path 下，精确匹配会整条失效。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (Boolean.FALSE.equals(accountProperties.signingEnabled())
                && ACCOUNT_DISABLED_OPERATIONS.contains(path)) {
            return true;
        }
        // 设备开关缺省为 false，故用「非 true 即关闭」判定，与 DeviceJwtProperties 的默认值一致。
        return !Boolean.TRUE.equals(deviceProperties.signingEnabled())
                && DEVICE_DISABLED_OPERATIONS.contains(path);
    }
}
