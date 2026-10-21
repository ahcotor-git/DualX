package com.dualx.api.security;

import com.dualx.api.support.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Raw JSON payloads retain incorrect types that JWTClaimsSet.Builder would otherwise normalize.
 * All HTTP cases use real signatures, the production decoder and the production security chain.
 */
@SpringBootTest(classes = SecurityJwtValidationIT.JwtApplication.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ActiveProfiles("sec02-test")
class SecurityJwtValidationIT {
    private static final String ISSUER = "scds-jwt-validation-test";
    private static final String PATH = "/actuator/info";
    private static final KeyPair KEY = newKeyPair();
    private static final KeyPair WRONG_KEY = newKeyPair();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfig.class, JwtDecoderConfig.class, ApiProblemAuthenticationEntryPoint.class,
            ApiProblemAccessDeniedHandler.class, TraceIdFilter.class})
    static class JwtApplication {
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("scds.auth.jwt.issuer", () -> ISSUER);
        registry.add("scds.auth.jwt.signing-enabled", () -> true);
        registry.add("scds.auth.jwt.public-key", SecurityJwtValidationIT::publicKeyPem);
        registry.add("scds.auth.jwt.public-key-file", () -> "");
        registry.add("scds.auth.jwt.private-key", () -> "");
        registry.add("scds.auth.jwt.private-key-file", () -> "");
        registry.add("scds.auth.jwt.jwks-uri", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApiProblemAuthenticationEntryPoint entryPoint;
    @Autowired TraceIdFilter traceFilter;

    @TestFactory
    Stream<DynamicTest> requiredClaimsAndIncorrectTypes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String claim : List.of("iss", "sub", "aud", "exp")) {
            tests.add(invalid("missing " + claim, values -> values.remove(claim)));
            tests.add(invalid("null " + claim, values -> values.put(claim, null)));
        }
        tests.add(invalid("wrong issuer", values -> values.put("iss", "another-instance")));
        tests.add(invalid("empty issuer", values -> values.put("iss", "")));
        tests.add(invalid("numeric issuer", values -> values.put("iss", 7)));
        tests.add(invalid("object issuer", values -> values.put("iss", Map.of("value", ISSUER))));
        tests.add(invalid("empty subject", values -> values.put("sub", "")));
        tests.add(invalid("blank subject", values -> values.put("sub", " ")));
        tests.add(invalid("numeric account subject", values -> values.put("sub", 7)));
        tests.add(invalid("non UUID account subject", values -> values.put("sub", "account")));
        tests.add(invalid("short UUID account subject", values -> values.put("sub", "1-1-1-1-1")));
        tests.add(invalid("uppercase UUID account subject",
                values -> values.put("sub", "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF")));
        tests.add(invalid("unknown audience", values -> values.put("aud", "untrusted")));
        tests.add(invalid("empty audience", values -> values.put("aud", "")));
        tests.add(invalid("numeric audience", values -> values.put("aud", 7)));
        tests.add(invalid("empty audience array", values -> values.put("aud", List.of())));
        tests.add(invalid("mixed token purposes",
                values -> values.put("aud", List.of("scds-edge", "scds-device"))));
        tests.add(invalid("known and unknown audiences",
                values -> values.put("aud", List.of("scds-edge", "untrusted"))));
        tests.add(invalid("duplicate audiences",
                values -> values.put("aud", List.of("scds-edge", "scds-edge"))));
        tests.add(invalid("non string audience element", values -> values.put("aud", List.of(7))));
        tests.add(invalid("string expiration", values -> values.put("exp", "tomorrow")));
        tests.add(invalid("boolean expiration", values -> values.put("exp", true)));
        tests.add(invalid("object expiration", values -> values.put("exp", Map.of())));
        tests.add(invalid("overflow expiration", values -> values.put("exp", Long.MAX_VALUE)));
        tests.add(invalid("iat equals exp", values -> values.put("iat", values.get("exp"))));
        tests.add(invalid("iat after exp", values -> values.put("iat",
                Instant.now().plusSeconds(600).getEpochSecond())));
        tests.add(invalid("roles string", values -> values.put("roles", "ADMIN")));
        tests.add(invalid("roles null", values -> values.put("roles", null)));
        tests.add(invalid("numeric role", values -> values.put("roles", List.of(7))));
        tests.add(invalid("unknown role", values -> values.put("roles", List.of("OWNER"))));
        for (String claim : List.of("iat", "nbf")) {
            tests.add(invalid("string " + claim, values -> values.put(claim, "now")));
            tests.add(invalid("null " + claim, values -> values.put(claim, null)));
            tests.add(invalid("array " + claim, values -> values.put(claim, List.of())));
        }
        tests.add(invalid("numeric jti", values -> values.put("jti", 7)));
        tests.add(invalid("null jti", values -> values.put("jti", null)));
        tests.add(invalid("empty jti", values -> values.put("jti", "")));
        tests.add(invalid("certificate-bound token without certificate",
                values -> values.put("cnf", Map.of("x5t#S256", "a".repeat(43)))));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> deviceIdentityStructureWithoutDatabaseSidValidation() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object subject : List.of(7, "0", "-1", "01", "65533", "65536", "vest-7", "")) {
            tests.add(invalidDevice("invalid device subject " + subject, values -> values.put("sub", subject)));
        }
        tests.add(invalidDevice("missing sid", values -> values.remove("sid")));
        tests.add(invalidDevice("null sid", values -> values.put("sid", null)));
        tests.add(invalidDevice("numeric sid", values -> values.put("sid", 7)));
        tests.add(invalidDevice("invalid sid", values -> values.put("sid", "session")));
        tests.add(invalidDevice("short sid", values -> values.put("sid", "1-1-1-1-1")));
        tests.add(invalidDevice("uppercase sid",
                values -> values.put("sid", "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF")));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> signatureAndTimeErrorsRemainDistinct() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String audience : List.of("scds-edge", "scds-device", "scds-refresh")) {
            tests.add(dynamicTest("expired " + audience, () -> {
                Map<String, Object> claims = claims(audience);
                expire(claims);
                expectProblem(sign(claims, KEY), 401, "AUTH_TOKEN_EXPIRED");
            }));
        }
        tests.add(invalid("future nbf",
                values -> values.put("nbf", Instant.now().plusSeconds(300).getEpochSecond())));
        tests.add(invalid("expired and wrong issuer", values -> {
            expire(values);
            values.put("iss", "another-instance");
        }));
        tests.add(invalid("expired and malformed roles", values -> {
            expire(values);
            values.put("roles", "ADMIN");
        }));
        tests.add(invalid("expired and future nbf", values -> {
            expire(values);
            values.put("nbf", Instant.now().plusSeconds(300).getEpochSecond());
        }));
        tests.add(invalid("expired and certificate binding failure", values -> {
            expire(values);
            values.put("cnf", Map.of("x5t#S256", "a".repeat(43)));
        }));
        tests.add(dynamicTest("expired but incorrect signature", () -> {
            Map<String, Object> claims = claims("scds-edge");
            expire(claims);
            expectProblem(sign(claims, WRONG_KEY), 401, "AUTH_TOKEN_INVALID");
        }));
        tests.add(dynamicTest("incorrect signature", () ->
                expectProblem(sign(claims("scds-edge"), WRONG_KEY), 401, "AUTH_TOKEN_INVALID")));
        tests.add(dynamicTest("HS256 is rejected", () -> {
            JWSObject jwt = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(claims("scds-edge")));
            jwt.sign(new MACSigner(new byte[32]));
            expectProblem(jwt.serialize(), 401, "AUTH_TOKEN_INVALID");
        }));
        tests.add(dynamicTest("unsigned token is rejected", () ->
                expectProblem(new PlainObject(new Payload(claims("scds-edge"))).serialize(),
                        401, "AUTH_TOKEN_INVALID")));
        tests.add(dynamicTest("malformed token is rejected", () ->
                expectProblem("not-a-jwt", 401, "AUTH_TOKEN_INVALID")));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> validTokensAndOptionalClaims() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("valid account", () -> expectAccepted(claims("scds-edge"), 200)));
        tests.add(dynamicTest("single audience array", () -> {
            Map<String, Object> claims = claims("scds-edge");
            claims.put("aud", List.of("scds-edge"));
            expectAccepted(claims, 200);
        }));
        tests.add(dynamicTest("optional iat nbf jti absent", () -> {
            Map<String, Object> claims = claims("scds-edge");
            for (String claim : List.of("iat", "nbf", "jti")) {
                claims.remove(claim);
            }
            expectAccepted(claims, 200);
        }));
        tests.add(dynamicTest("roles absent is valid but not ADMIN", () -> {
            Map<String, Object> claims = claims("scds-edge");
            claims.remove("roles");
            expectProblem(sign(claims, KEY), 403, "AUTH_FORBIDDEN");
        }));
        tests.add(dynamicTest("empty roles is valid but not ADMIN", () -> {
            Map<String, Object> claims = claims("scds-edge");
            claims.put("roles", List.of());
            expectProblem(sign(claims, KEY), 403, "AUTH_FORBIDDEN");
        }));
        tests.add(dynamicTest("fractional NumericDate", () -> {
            Map<String, Object> claims = claims("scds-edge");
            claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond() + 0.5);
            expectAccepted(claims, 200);
        }));
        for (String subject : List.of("1", "65532")) {
            tests.add(dynamicTest("valid device identity " + subject + " cannot read ADMIN info", () -> {
                Map<String, Object> claims = claims("scds-device");
                claims.put("sub", subject);
                expectProblem(sign(claims, KEY), 403, "AUTH_FORBIDDEN");
            }));
        }
        tests.add(dynamicTest("valid refresh purpose cannot read ADMIN info", () ->
                expectProblem(sign(claims("scds-refresh"), KEY), 403, "AUTH_FORBIDDEN")));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> sixtySecondClockSkewHasDeterministicBoundaries() {
        Instant now = Instant.parse("2026-10-21T00:00:00Z");
        JwtDecoder decoder = new JwtDecoderConfig().jwtDecoder(localProperties(),
                Clock.fixed(now, ZoneOffset.UTC));
        List<DynamicTest> tests = new ArrayList<>();
        for (int seconds : List.of(-61, -60, -59)) {
            tests.add(dynamicTest("exp offset " + seconds, () -> {
                Map<String, Object> values = claims("scds-edge");
                values.remove("iat");
                values.remove("nbf");
                values.put("exp", now.plusSeconds(seconds).getEpochSecond());
                String encoded = sign(values, KEY);
                if (seconds < -60) {
                    expectDecoderError(decoder, encoded, "AUTH_TOKEN_EXPIRED");
                } else {
                    assertThat(decoder.decode(encoded).getSubject()).isEqualTo(values.get("sub"));
                }
            }));
        }
        for (int seconds : List.of(59, 60, 61)) {
            tests.add(dynamicTest("nbf offset " + seconds, () -> {
                Map<String, Object> values = claims("scds-edge");
                values.remove("iat");
                values.put("exp", now.plusSeconds(300).getEpochSecond());
                values.put("nbf", now.plusSeconds(seconds).getEpochSecond());
                String encoded = sign(values, KEY);
                if (seconds > 60) {
                    expectDecoderError(decoder, encoded, "AUTH_TOKEN_INVALID");
                } else {
                    assertThat(decoder.decode(encoded).getSubject()).isEqualTo(values.get("sub"));
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void localDeploymentRejectsJwksWithoutFetching() throws Exception {
        // A loopback tripwire proves that rejected configuration never fetches keys.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        byte[] keys = new JWKSet(new RSAKey.Builder((RSAPublicKey) KEY.getPublic()).build())
                .toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keys.length);
            try (var body = exchange.getResponseBody()) {
                body.write(keys);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            assertThatThrownBy(() -> new AuthJwtProperties(ISSUER, false,
                    null, null, null, null, uri, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Local deployment requires PEM keys; remote JWKS is not supported.")
                    .hasMessageNotContaining(uri);
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @TestFactory
    Stream<DynamicTest> entryPointClassifiesOnlyMachineCodes() {
        OAuth2Error expired = new OAuth2Error("AUTH_TOKEN_EXPIRED", "登录状态已过期", null);
        OAuth2Error fakeExpired = new OAuth2Error("AUTH_TOKEN_INVALID", "Jwt expired fabricated", null);
        return Stream.of(
                classification("expired code with localized description", List.of(expired), "AUTH_TOKEN_EXPIRED"),
                classification("expired code with no description",
                        List.of(new OAuth2Error("AUTH_TOKEN_EXPIRED", null, null)), "AUTH_TOKEN_EXPIRED"),
                classification("invalid code with expired wording", List.of(fakeExpired), "AUTH_TOKEN_INVALID"),
                classification("unknown code with expired wording",
                        List.of(new OAuth2Error("invalid_token", "Jwt expired fabricated", null)),
                        "AUTH_TOKEN_INVALID"),
                classification("mixed expired and invalid codes", List.of(
                        new OAuth2Error("AUTH_TOKEN_EXPIRED", "Jwt expired at some time", null),
                        new OAuth2Error("AUTH_TOKEN_INVALID", "issuer failed", null)), "AUTH_TOKEN_INVALID"),
                classification("all errors are expired", List.of(
                        new OAuth2Error("AUTH_TOKEN_EXPIRED", "Jwt expired at some time", null), expired),
                        "AUTH_TOKEN_EXPIRED"),
                dynamicTest("message alone cannot identify expiry", () ->
                        expectClassification(new BadCredentialsException("Jwt expired fabricated"),
                                "AUTH_TOKEN_INVALID")),
                dynamicTest("wrapped validation exception retains machine code", () ->
                        expectClassification(new BadCredentialsException("outer wrapper",
                                new InvalidBearerTokenException("validation failure",
                                        new JwtValidationException("validation failure", List.of(expired)))),
                                "AUTH_TOKEN_EXPIRED")));
    }

    private DynamicTest classification(String name, List<OAuth2Error> errors, String code) {
        return dynamicTest(name, () -> expectClassification(
                new InvalidBearerTokenException("validation failure",
                        new JwtValidationException("validation failure", errors)), code));
    }

    private void expectClassification(AuthenticationException failure, String code) throws Exception {
        // Synthetic error descriptors supplement the real JWT cases; the decoder is never mocked.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceIdFilter.HEADER, "sec02-machine-code");
        traceFilter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                entryPoint.commence(request, response, failure));
        var problem = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("instance").asText()).isEqualTo(PATH);
        assertThat(problem.path("traceId").asText()).isEqualTo("sec02-machine-code");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("sec02-machine-code");
    }

    private static AuthJwtProperties localProperties() {
        return new AuthJwtProperties(ISSUER, false, publicKeyPem(),
                null, null, null, null, null, null, null, null);
    }

    private static void expectDecoderError(JwtDecoder decoder, String encoded, String code) {
        assertThatThrownBy(() -> decoder.decode(encoded))
                .isInstanceOfSatisfying(JwtValidationException.class, failure ->
                        assertThat(failure.getErrors()).extracting(error -> error.getErrorCode())
                                .containsExactly(code));
    }

    private DynamicTest invalid(String name, Consumer<Map<String, Object>> mutate) {
        return dynamicTest(name, () -> {
            Map<String, Object> values = claims("scds-edge");
            mutate.accept(values);
            expectProblem(sign(values, KEY), 401, "AUTH_TOKEN_INVALID");
        });
    }

    private DynamicTest invalidDevice(String name, Consumer<Map<String, Object>> mutate) {
        return dynamicTest(name, () -> {
            Map<String, Object> values = claims("scds-device");
            mutate.accept(values);
            expectProblem(sign(values, KEY), 401, "AUTH_TOKEN_INVALID");
        });
    }

    private void expectAccepted(Map<String, Object> claims, int expectedStatus) throws Exception {
        mvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + sign(claims, KEY)))
                .andExpect(status().is(expectedStatus));
    }

    private void expectProblem(String encoded, int expectedStatus, String code) throws Exception {
        MvcResult result = mvc.perform(get(PATH).header("Authorization", "Bearer " + encoded))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andReturn();
        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).isNotBlank();
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray())
                .path("traceId").asText()).isEqualTo(traceId);
    }

    private static Map<String, Object> claims(String audience) {
        Instant now = Instant.now();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("iss", ISSUER);
        values.put("sub", "scds-device".equals(audience) ? "7" : UUID.randomUUID().toString());
        values.put("aud", audience);
        values.put("exp", now.plusSeconds(300).getEpochSecond());
        values.put("iat", now.getEpochSecond());
        values.put("nbf", now.minusSeconds(1).getEpochSecond());
        values.put("jti", UUID.randomUUID().toString());
        if ("scds-edge".equals(audience)) {
            values.put("roles", List.of("ADMIN"));
        } else if ("scds-device".equals(audience)) {
            values.put("sid", UUID.randomUUID().toString());
        }
        return values;
    }

    private static void expire(Map<String, Object> values) {
        Instant now = Instant.now();
        values.put("iat", now.minusSeconds(600).getEpochSecond());
        values.put("nbf", now.minusSeconds(601).getEpochSecond());
        values.put("exp", now.minusSeconds(300).getEpochSecond());
    }

    private static String sign(Map<String, Object> claims, KeyPair key) throws Exception {
        JWSObject jwt = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException("RSA is required for JWT validation tests", failure);
        }
    }

    private static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(KEY.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }
}
