package com.dualx.security;

import com.dualx.api.security.ApiProblemAccessDeniedHandler;
import com.dualx.api.security.ApiProblemAuthenticationEntryPoint;
import com.dualx.api.security.AuthJwtProperties;
import com.dualx.api.security.JwtDecoderConfig;
import com.dualx.api.security.SecurityConfig;
import com.dualx.api.support.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "scds.auth.jwt.signing-enabled=true")
class SecurityMetricsIT extends SecurityMetricsAssertions {
    @TestFactory
    Stream<DynamicTest> metricsAccessMatrix() {
        return cases();
    }

    @Override
    boolean expectedSigningEnabled() {
        return true;
    }
}

@TestPropertySource(properties = "scds.auth.jwt.signing-enabled=false")
class SecurityMetricsReadOnlyIT extends SecurityMetricsAssertions {
    @TestFactory
    Stream<DynamicTest> metricsAccessMatrix() {
        return cases();
    }

    @Override
    boolean expectedSigningEnabled() {
        return false;
    }
}

/**
 * Real Actuator endpoints and production security filters, without database or hardware beans.
 * Test-only signing keys stay in memory; request dumps are disabled to keep tokens out of reports.
 */
@SpringBootTest(classes = SecurityMetricsAssertions.MetricsApplication.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ActiveProfiles("sec01-test")
abstract class SecurityMetricsAssertions {

    private static final String ISSUER = "scds-metrics-test";
    private static final KeyPair KEY = newKeyPair();
    private static final KeyPair WRONG_KEY = newKeyPair();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtDecoderConfig.class, ApiProblemAuthenticationEntryPoint.class,
            ApiProblemAccessDeniedHandler.class, TraceIdFilter.class})
    static class MetricsApplication {
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("scds.auth.jwt.issuer", () -> ISSUER);
        registry.add("scds.auth.jwt.public-key", () -> publicKeyPem(KEY));
        registry.add("scds.auth.jwt.public-key-file", () -> "");
        registry.add("scds.auth.jwt.private-key", () -> "");
        registry.add("scds.auth.jwt.private-key-file", () -> "");
        registry.add("scds.auth.jwt.jwks-uri", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthJwtProperties properties;
    @Autowired ApplicationContext context;

    abstract boolean expectedSigningEnabled();

    Stream<DynamicTest> cases() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("requested capability and no DataSource",
                this::contextUsesRequestedCapabilityAndDoesNotConnectToDatabase));
        for (String path : List.of("/actuator/metrics", "/actuator/metrics/jvm.memory.used")) {
            tests.add(dynamicTest("missing bearer: " + path, () -> missingBearerIsUnauthorized(path)));
            tests.add(dynamicTest("incorrect signature: " + path,
                    () -> incorrectSignatureIsUnauthorized(path)));
            for (String audience : List.of("scds-device", "scds-refresh")) {
                tests.add(dynamicTest(audience + " forbidden: " + path,
                        () -> otherTokenPurposesAreForbidden(audience, path)));
            }
        }
        for (String role : List.of("VIEWER", "DIRECTOR", "OPERATOR")) {
            for (String path : List.of("/actuator/metrics/jvm.memory.used",
                    "/actuator/metrics/jvm.memory.used?tag=area:heap",
                    "/actuator/metrics/nonexistent.metric", "/actuator/metrics")) {
                tests.add(dynamicTest(role + " forbidden: " + path,
                        () -> nonAdminCannotReadAnyMetricPath(role, path)));
            }
        }
        tests.add(dynamicTest("ADMIN reads metric index", this::adminReadsRealMetricsIndex));
        for (String path : List.of("/actuator/metrics/jvm.memory.used",
                "/actuator/metrics/jvm.memory.used?tag=area:heap")) {
            tests.add(dynamicTest("ADMIN reads measurements: " + path,
                    () -> adminReadsRealMetricMeasurements(path)));
        }
        tests.add(dynamicTest("public health", this::healthRemainsPublic));
        tests.add(dynamicTest("supplied trace ID", this::forbiddenResponsePreservesSuppliedTraceId));
        return tests.stream();
    }

    void contextUsesRequestedCapabilityAndDoesNotConnectToDatabase() {
        assertThat(properties.signingEnabled()).isEqualTo(expectedSigningEnabled());
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    void missingBearerIsUnauthorized(String path) throws Exception {
        expectProblem(get(path), path, 401, "AUTH_TOKEN_INVALID");
    }

    void incorrectSignatureIsUnauthorized(String path) throws Exception {
        expectProblem(get(path).header("Authorization",
                "Bearer " + token(WRONG_KEY, "scds-edge", List.of("ADMIN"))),
                path, 401, "AUTH_TOKEN_INVALID");
    }

    void nonAdminCannotReadAnyMetricPath(String role, String path) throws Exception {
        expectProblem(get(path).header("Authorization",
                "Bearer " + token(KEY, "scds-edge", List.of(role))),
                path.split("\\?")[0], 403, "AUTH_FORBIDDEN");
    }

    void otherTokenPurposesAreForbidden(String audience, String path) throws Exception {
        expectProblem(get(path).header("Authorization", "Bearer " + token(KEY, audience, List.of())),
                path, 403, "AUTH_FORBIDDEN");
    }

    void adminReadsRealMetricsIndex() throws Exception {
        mvc.perform(get("/actuator/metrics").accept(MediaType.APPLICATION_JSON).header("Authorization",
                        "Bearer " + token(KEY, "scds-edge", List.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.names", hasItem("jvm.memory.used")));
    }

    void adminReadsRealMetricMeasurements(String path) throws Exception {
        mvc.perform(get(path).accept(MediaType.APPLICATION_JSON).header("Authorization",
                        "Bearer " + token(KEY, "scds-edge", List.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("jvm.memory.used"))
                .andExpect(jsonPath("$.measurements", not(empty())))
                .andExpect(jsonPath("$.measurements[0].statistic").value("VALUE"))
                .andExpect(jsonPath("$.measurements[0].value").isNumber());
    }

    void healthRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    void forbiddenResponsePreservesSuppliedTraceId() throws Exception {
        String traceId = "sec01-supplied-trace";
        MvcResult result = expectProblem(get("/actuator/metrics")
                        .header(TraceIdFilter.HEADER, traceId)
                        .header("Authorization", "Bearer " + token(KEY, "scds-edge", List.of("VIEWER"))),
                "/actuator/metrics", 403, "AUTH_FORBIDDEN");
        assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER)).isEqualTo(traceId);
    }

    private MvcResult expectProblem(MockHttpServletRequestBuilder request, String path,
                                    int expectedStatus, String code) throws Exception {
        MvcResult result = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.instance").value(path))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
                .path("traceId").asText()).isEqualTo(traceId);
        return result;
    }

    private static String token(KeyPair key, String audience, List<String> roles) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("scds-device".equals(audience) ? "7" : UUID.randomUUID().toString())
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .jwtID(UUID.randomUUID().toString());
        if ("scds-edge".equals(audience)) {
            claims.claim("roles", roles);
        } else if ("scds-device".equals(audience)) {
            claims.claim("sid", UUID.randomUUID().toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
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
}
