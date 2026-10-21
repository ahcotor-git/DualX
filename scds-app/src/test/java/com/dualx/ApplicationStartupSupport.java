package com.dualx;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Test-only boot harness. No replacement beans, persisted keys or development configuration. */
final class ApplicationStartupSupport {
    static final String URL =
            "jdbc:mysql://127.0.0.1:13306/scds_next_test?rewriteBatchedStatements=true";
    static final String ISSUER = "scds-full-startup-test";
    static final String PROFILE = "full-startup-test";
    static final String GUARD_MESSAGE = "TEST-01 full startup requires isolated local test settings.";

    private ApplicationStartupSupport() {
    }

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static ConfigurableEnvironment environment(boolean signing, KeyPair key) {
        Properties credentials = new Properties();
        List<Path> candidates = List.of(Path.of(".env.test"), Path.of("../.env.test"))
                .stream().filter(Files::isRegularFile).toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException("TEST-01 requires exactly one local .env.test file.");
        }
        try (var reader = Files.newBufferedReader(candidates.getFirst(), StandardCharsets.UTF_8)) {
            credentials.load(reader);
        } catch (IOException | IllegalArgumentException failure) {
            // Do not include parser input or credentials in failure diagnostics.
            throw new IllegalStateException("TEST-01 could not read the isolated test credentials.");
        }
        if (!credentials.stringPropertyNames().equals(Set.of(
                "SCDS_TEST_JDBC_URL", "SCDS_TEST_MYSQL_USER", "SCDS_TEST_MYSQL_PASSWORD"))) {
            throw new IllegalStateException("TEST-01 credential file has unexpected or missing keys.");
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        // Restrict config-data discovery before SpringApplication runs, not after importing local .env.
        settings.put("spring.config.location", "classpath:/application.yml");
        settings.put("spring.config.additional-location", "");
        settings.put("spring.config.import", "");
        settings.put("spring.profiles.active", PROFILE);
        settings.put("spring.profiles.include", "");
        settings.put("spring.datasource.url", credentials.getProperty("SCDS_TEST_JDBC_URL"));
        settings.put("spring.datasource.username", credentials.getProperty("SCDS_TEST_MYSQL_USER"));
        settings.put("spring.datasource.password", credentials.getProperty("SCDS_TEST_MYSQL_PASSWORD"));
        settings.put("spring.datasource.hikari.maximum-pool-size", "2");
        settings.put("spring.datasource.hikari.data-source-properties.connectionTimeZone", "UTC");
        settings.put("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession", "true");
        settings.put("spring.flyway.clean-disabled", "true");
        settings.put("spring.flyway.out-of-order", "false");
        settings.put("server.address", "127.0.0.1");
        settings.put("server.port", "0");
        settings.put("scds.admin.username", "");
        settings.put("scds.admin.password", "");
        settings.put("scds.admin.display-name", "");
        settings.put("scds.auth.jwt.issuer", ISSUER);
        settings.put("scds.auth.jwt.signing-enabled", Boolean.toString(signing));
        settings.put("scds.auth.jwt.public-key", pem("PUBLIC KEY", key.getPublic().getEncoded()));
        settings.put("scds.auth.jwt.public-key-file", "");
        settings.put("scds.auth.jwt.private-key", signing ? pem("PRIVATE KEY", key.getPrivate().getEncoded()) : "");
        settings.put("scds.auth.jwt.private-key-file", "");
        settings.put("scds.auth.jwt.jwks-uri", "");

        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("full-startup-test", settings));
        verify(environment);
        return environment;
    }

    static ConfigurableApplicationContext start(ConfigurableEnvironment environment) {
        verify(environment);
        SpringApplication application = new SpringApplication(ScdsNextApplication.class);
        application.setEnvironment(environment);
        application.setRegisterShutdownHook(false);
        // Recheck resolved config after discovery, before DataSource, Flyway, server or runners exist.
        application.addInitializers(ApplicationStartupSupport::installGuard);
        // Other ITs contain plain @Configuration classes on the same test classpath.
        // Keep component scanning identical to the packaged app: only production components.
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "fullStartupTestClassFilter", new TypeExcludeFilter() {
                    @Override
                    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
                        return reader.getResource().getURL().toExternalForm().contains("/test-classes/");
                    }
                }));
        return application.run();
    }

    static void installGuard(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(ignored -> verify(context.getEnvironment()));
    }

    static void verify(ConfigurableEnvironment environment) {
        String password;
        try {
            password = environment.getProperty("spring.datasource.password", "");
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException(GUARD_MESSAGE);
        }
        if (!Set.of(URL, URL.replace("127.0.0.1", "localhost"))
                .contains(environment.getProperty("spring.datasource.url", ""))
                || !"scds_test".equals(environment.getProperty("spring.datasource.username"))
                || password.isBlank() || password.contains("${")
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"0".equals(environment.getProperty("server.port"))
                || !PROFILE.equals(environment.getProperty("spring.profiles.active"))
                || !"classpath:/application.yml".equals(environment.getProperty("spring.config.location"))
                || !environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true)
                || environment.getProperty("spring.flyway.out-of-order", Boolean.class, false)) {
            throw new IllegalStateException(GUARD_MESSAGE);
        }
        for (String property : Set.of("spring.config.import", "spring.config.additional-location",
                "spring.profiles.include", "scds.admin.username", "scds.admin.password", "scds.admin.display-name",
                "scds.auth.jwt.public-key-file", "scds.auth.jwt.private-key-file", "scds.auth.jwt.jwks-uri")) {
            if (!environment.getProperty(property, "").isEmpty()) {
                throw new IllegalStateException(GUARD_MESSAGE);
            }
        }
        for (String property : Set.of("spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
                "spring.flyway.default-schema", "spring.flyway.schemas", "spring.datasource.jndi-name",
                "spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.username",
                "spring.datasource.hikari.password", "spring.datasource.hikari.data-source-class-name",
                "spring.datasource.hikari.data-source-properties.user",
                "spring.datasource.hikari.data-source-properties.password",
                "spring.datasource.hikari.data-source-properties.url", "management.server.port")) {
            if (environment.containsProperty(property)) {
                throw new IllegalStateException(GUARD_MESSAGE);
            }
        }
        if (Set.of("spring.config.import[0]", "spring.profiles.include[0]",
                "spring.profiles.group." + PROFILE + "[0]").stream().anyMatch(environment::containsProperty)) {
            throw new IllegalStateException(GUARD_MESSAGE);
        }
    }

    static String externalAccessToken(KeyPair key, List<String> roles) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(UUID.randomUUID().toString()).audience("scds-edge")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("roles", roles).build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static String pem(String kind, byte[] encoded) {
        return "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END " + kind + "-----\n";
    }
}
