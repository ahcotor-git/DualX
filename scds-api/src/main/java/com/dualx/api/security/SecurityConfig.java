package com.dualx.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 安全过滤链与免鉴权白名单（设计 03 §4.4 / §4.6 / §4.7）。
 *
 * <p><b>默认拒绝</b>：白名单外的任何请求都需要令牌，包括两个 WS 握手与
 * {@code /actuator/info|metrics}。这与「先全放开再逐个收紧」相反——后者迟早会漏掉一个。</p>
 *
 * <p><b>白名单是只增不改清单</b>：每新增一条 {@code permitAll}，必须回到
 * docs/design/03-auth.md §4.4 登记一行。鉴权漏洞几乎从来不是「判断写错」，
 * 而是「某次联调顺手放行了一个端点，然后再没人删」。</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 账号令牌用途：指挥台 / 单兵 App 的业务接口与 WS 都要求它。 */
    private static final String AUD_EDGE = "AUD_scds-edge";

    /** 设备令牌用途：只有设备自己的端点认它。 */
    private static final String AUD_DEVICE = "AUD_scds-device";

    /** {@code roles} claim 到 Spring Security 权限的前缀，配合 {@code hasRole()} 使用。 */
    private static final String ROLE_PREFIX = "ROLE_";

    private final ApiProblemAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiProblemAccessDeniedHandler accessDeniedHandler;
    private final AuthJwtProperties properties;

    public SecurityConfig(ApiProblemAuthenticationEntryPoint authenticationEntryPoint,
                          ApiProblemAccessDeniedHandler accessDeniedHandler,
                          AuthJwtProperties properties) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // 纯 Bearer 令牌、无 Cookie 会话，CSRF 无作用面；留着反而会让 Flutter 端的
                // POST 全部 403，且错误信息完全指不到「CSRF」上。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(this::authorize)
                // 401/403 必须长成 ApiProblem（含 code + traceId）。两处都要设：
                //   - exceptionHandling：覆盖「压根没带令牌」这类走 ExceptionTranslationFilter 的场景；
                //   - oauth2ResourceServer：覆盖「带了令牌但不成立」的场景，它会用自己的
                //     BearerTokenAuthenticationEntryPoint 抢在 exceptionHandling 之前写响应。
                // 只设一处就会出现「有的 401 是 Problem、有的 401 是空体」这种最难查的组合。
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private void authorize(
            org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry registry) {
        // CORS 预检一律放行。Flutter 桌面/安卓不发 Origin，眼下用不到；但一旦将来接 Web
        // 控制台，预检被拦会表现为「连登录请求都发不出去」，那种现象极难联想到鉴权。
        registry.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();

        // 探活与 Boot 内部错误转发：探活必须免鉴权，否则负载均衡器会把「没带令牌」
        // 当成实例不健康；/error 是 Boot 的错误页转发目标，拦它会把所有错误变成 401。
        registry.requestMatchers(HttpMethod.GET, "/actuator/health", "/error").permitAll();

        // 设备登录恒在：它属于边缘本地事务（校验 t_vest 绑定关系），与账号签发能力无关。
        registry.requestMatchers(HttpMethod.POST, "/api/v1/auth/device/login").permitAll();

        if (properties.signingEnabled()) {
            // 仅在具备签发能力时才放行——否则这两个端点根本没注册（404），
            // 放行一条不存在的路径只会让白名单与真实路由对不上账。
            registry.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll();
        }

        // 指标会泄露内部结构与容量，不对外开放。
        registry.requestMatchers(HttpMethod.GET, "/actuator/info", "/actuator/metrics")
                .hasRole("ADMIN");

        // 设备令牌只能访问设备自己的端点。
        registry.requestMatchers("/api/v1/auth/device/**").hasAuthority(AUD_DEVICE);

        // 单兵终端要读自己的实时状态，指挥台也要看；两者都允许。
        registry.requestMatchers("/api/v1/situation/vests/**").hasAnyAuthority(AUD_DEVICE, AUD_EDGE);

        // WS 只认账号令牌（设计 03 §4.7）：契约里 WS 帧体是统一的 SituationEnvelope，
        // 没有「按会话裁剪字段」的定义，放开设备令牌就必须在扇出时逐会话过滤，
        // 与设计 02 的均匀广播直接冲突。设备令牌连 WS 得到 403（已认证但无权）。
        registry.requestMatchers("/ws/**").hasAuthority(AUD_EDGE);

        // 其余一切（含全部业务接口与 /api/v1/auth/me）都要求账号令牌。
        // 这条同时也是设备令牌的横向越权防线：aud 不对 → 403 而不是 401。
        registry.anyRequest().hasAuthority(AUD_EDGE);
    }

    /**
     * 把 JWT 的 claim 映射成权限：{@code roles} → {@code ROLE_*}，{@code aud} → {@code AUD_*}。
     *
     * <p>{@code aud} 也进权限，是为了让「已认证但用途不对」能落在授权阶段（403），
     * 而不是在验签阶段被当成令牌不成立（401）。这个区分对客户端行为影响很大：
     * 401 会让它去刷新令牌，而它在任何一种刷法下都拿不到能用的令牌，于是无限循环。</p>
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                for (String role : roles) {
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
                }
            }
            List<String> audience = jwt.getAudience();
            if (audience != null) {
                for (String value : audience) {
                    authorities.add(new SimpleGrantedAuthority("AUD_" + value));
                }
            }
            return authorities;
        });
        return converter;
    }
}
