package it.itsprodigi.proofchain.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.auth.application.JwtProperties;
import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageProperties;
import it.itsprodigi.proofchain.operator.application.BootstrapAdminProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Binding and validation contract for every externalized property the release baseline constrains.
 *
 * <p>Each case drives the real Spring binder against the real properties class, so a rejected value is a rejected
 * application context, not a logged warning. The valid cases pin the frozen defaults so a later edit that silently
 * widens a limit is caught here.
 */
class ConfigurationBindingTest {

    private static final String STRONG_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    // ----------------------------------------------------------------------------------------- JWT

    @Test
    void jwtBindsAStrongSecretAndAPositiveTtl() {
        runner(JwtBinding.class)
                .withPropertyValues("proofchain.jwt.secret=" + STRONG_SECRET, "proofchain.jwt.access-token-ttl=PT30M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(properties.decodedSecret()).hasSize(JwtProperties.MINIMUM_SECRET_BYTES);
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    proofchain.jwt.secret=            | proofchain.jwt.secret must be provided
                    proofchain.jwt.secret=not base64! | proofchain.jwt.secret must be standard Base64
                    proofchain.jwt.secret=c2hvcnQ=    | must decode to at least 32 bytes
                    """)
    void jwtRejectsMissingMalformedAndWeakSecrets(String property, String message) {
        assertBindingFails(JwtBinding.class, message, property, "proofchain.jwt.access-token-ttl=PT30M");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "-PT30M"})
    void jwtRejectsNonPositiveTokenTtl(String ttl) {
        assertBindingFails(
                JwtBinding.class,
                "proofchain.jwt.access-token-ttl must be positive",
                "proofchain.jwt.secret=" + STRONG_SECRET,
                "proofchain.jwt.access-token-ttl=" + ttl);
    }

    // ------------------------------------------------------------------------------------ Passwords

    @Test
    void passwordPolicyBindsTheFrozenDefaults() {
        runner(PasswordBinding.class)
                .withPropertyValues(
                        "proofchain.password.min-length=12",
                        "proofchain.password.max-length=128",
                        "proofchain.password.bcrypt-strength=12")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PasswordSecurityProperties properties = context.getBean(PasswordSecurityProperties.class);
                    assertThat(properties.getMinLength()).isEqualTo(12);
                    assertThat(properties.getMaxLength()).isEqualTo(128);
                    assertThat(properties.getBcryptStrength()).isEqualTo(12);
                    assertThat(properties.isLengthRangeOrdered()).isTrue();
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    proofchain.password.min-length=0        | min-length must be positive
                    proofchain.password.max-length=0        | max-length must be positive
                    proofchain.password.min-length=200      | must not exceed
                    proofchain.password.bcrypt-strength=3   | must be between 4 and 31
                    proofchain.password.bcrypt-strength=32  | must be between 4 and 31
                    """)
    void passwordPolicyRejectsInvalidPolicyAndStrength(String property, String message) {
        assertBindingFails(PasswordBinding.class, message, property);
    }

    // ------------------------------------------------------------------------------------ Multipart

    @Test
    void multipartBindsThePreservedFiftyMegabyteFileLimit() {
        runner(MultipartBinding.class)
                .withPropertyValues(
                        "spring.servlet.multipart.max-file-size=50MB", "spring.servlet.multipart.max-request-size=51MB")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MultipartLimitsProperties properties = context.getBean(MultipartLimitsProperties.class);
                    assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
                    assertThat(properties.maxRequestSize()).isEqualTo(DataSize.ofMegabytes(51));
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    0B    | 51MB | max-file-size must be greater than zero
                    50MB  | 0B   | max-request-size must be greater than zero
                    50MB  | 49MB | max-request-size must not be smaller than max-file-size
                    """)
    void multipartRejectsNonPositiveAndInconsistentLimits(String file, String request, String message) {
        assertBindingFails(
                MultipartBinding.class,
                message,
                "spring.servlet.multipart.max-file-size=" + file,
                "spring.servlet.multipart.max-request-size=" + request);
    }

    // ------------------------------------------------------------------------------- Server limits

    @Test
    void serverLimitsBindEveryNonFileBoundaryAndGracefulShutdown() {
        runner(ServerLimitsBinding.class).withPropertyValues(serverLimits()).run(context -> {
            assertThat(context).hasNotFailed();
            HttpRequestLimitsProperties properties = context.getBean(HttpRequestLimitsProperties.class);
            assertThat(properties.gracefulShutdownEnabled()).isTrue();
            assertThat(properties.maxHttpRequestHeaderSize()).isEqualTo(DataSize.ofKilobytes(16));
            assertThat(properties.tomcat().maxHttpFormPostSize()).isEqualTo(DataSize.ofKilobytes(256));
            assertThat(properties.tomcat().maxSwallowSize()).isEqualTo(DataSize.ofMegabytes(2));
            assertThat(properties.tomcat().maxParameterCount()).isEqualTo(256);
            assertThat(properties.tomcat().maxPartCount()).isEqualTo(16);
            assertThat(properties.tomcat().maxPartHeaderSize()).isEqualTo(DataSize.ofKilobytes(8));
            assertThat(properties.tomcat().connectionTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.tomcat().keepAliveTimeout()).isEqualTo(Duration.ofSeconds(20));
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    server.shutdown=immediate                  | server.shutdown must be graceful
                    server.max-http-request-header-size=0B      | max-http-request-header-size must be a bounded
                    server.tomcat.max-http-form-post-size=-1B    | max-http-form-post-size must be a bounded
                    server.tomcat.max-swallow-size=-1B           | max-swallow-size must be a bounded
                    server.tomcat.max-parameter-count=0          | max-parameter-count must be a bounded
                    server.tomcat.max-part-count=-1              | max-part-count must be a bounded
                    server.tomcat.max-part-header-size=0B        | max-part-header-size must be a bounded
                    server.tomcat.connection-timeout=0s          | connection-timeout must be a bounded
                    server.tomcat.keep-alive-timeout=-5s         | keep-alive-timeout must be a bounded
                    """)
    void serverLimitsRejectUnboundedOrDegradedValues(String override, String message) {
        String[] properties = new String[serverLimits().length + 1];
        System.arraycopy(serverLimits(), 0, properties, 0, serverLimits().length);
        properties[properties.length - 1] = override;
        assertBindingFails(ServerLimitsBinding.class, message, properties);
    }

    // ---------------------------------------------------------------------------- Database timeouts

    @Test
    void databaseTimeoutsBindBoundedAcquisitionStartupAndLockBudgets() {
        runner(DatabaseTimeoutBinding.class)
                .withPropertyValues(databaseTimeouts())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DatabaseTimeoutProperties properties = context.getBean(DatabaseTimeoutProperties.class);
                    assertThat(properties.connectionTimeout()).isEqualTo(10_000L);
                    assertThat(properties.validationTimeout()).isEqualTo(5_000L);
                    assertThat(properties.initializationFailTimeout()).isEqualTo(1L);
                    assertThat(properties.lockTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    spring.datasource.hikari.connection-timeout=100             | connection-timeout must be between
                    spring.datasource.hikari.connection-timeout=600000          | connection-timeout must be between
                    spring.datasource.hikari.validation-timeout=100             | validation-timeout must be between
                    spring.datasource.hikari.validation-timeout=10000           | validation-timeout must be smaller
                    spring.datasource.hikari.initialization-fail-timeout=-1     | must not be negative
                    spring.datasource.hikari.connection-init-sql=SELECT 1       | must be exactly
                    spring.datasource.hikari.connection-init-sql=SET lock_timeout = '10min' | must set a lock_timeout
                    """)
    void databaseTimeoutsRejectUnboundedRetryingOrDegradedValues(String override, String message) {
        String[] properties = new String[databaseTimeouts().length + 1];
        System.arraycopy(databaseTimeouts(), 0, properties, 0, databaseTimeouts().length);
        properties[properties.length - 1] = override;
        assertBindingFails(DatabaseTimeoutBinding.class, message, properties);
    }

    // ------------------------------------------------------------------------------------ Shutdown

    @Test
    void gracefulShutdownBindsAFiniteBudget() {
        runner(ShutdownBinding.class)
                .withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GracefulShutdownProperties.class).timeoutPerShutdownPhase())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "10m"})
    void gracefulShutdownRejectsNonPositiveAndUnreasonableBudgets(String timeout) {
        assertBindingFails(
                ShutdownBinding.class,
                "timeout-per-shutdown-phase must be a positive duration",
                "spring.lifecycle.timeout-per-shutdown-phase=" + timeout);
    }

    // ---------------------------------------------------------------------------- Runtime datasource

    @Test
    void runtimeDatasourceBindsPostgreSqlCoordinates() {
        runner(RuntimeConfigurationValidation.RuntimeDatasourceValidation.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/proofchain",
                        "spring.datasource.username=proofchain",
                        "spring.datasource.password=configured-through-the-environment")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RuntimeDatasourceProperties.class)
                                    .url())
                            .startsWith(RuntimeDatasourceProperties.REQUIRED_URL_PREFIX);
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    spring.datasource.url=                  | spring.datasource.url must be configured
                    spring.datasource.username=             | spring.datasource.username must be provided
                    spring.datasource.password=             | spring.datasource.password must be provided
                    spring.datasource.url=jdbc:mysql://db/x | must start with jdbc:postgresql://
                    """)
    void runtimeDatasourceRejectsMissingCredentialsAndUnsupportedDatabases(String override, String message) {
        assertBindingFails(
                RuntimeConfigurationValidation.RuntimeDatasourceValidation.class,
                message,
                "spring.profiles.active=local",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/proofchain",
                "spring.datasource.username=proofchain",
                "spring.datasource.password=configured-through-the-environment",
                override);
    }

    // ------------------------------------------------------------------------------ Evidence storage

    @Test
    void evidenceStorageBindsARootAndAPositiveFileLimit() {
        runner(StorageBinding.class)
                .withPropertyValues("proofchain.storage.root=./storage", "proofchain.storage.max-file-size=50MB")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EvidenceStorageProperties properties = context.getBean(EvidenceStorageProperties.class);
                    assertThat(properties.root().toAbsolutePath().normalize())
                            .isEqualTo(Path.of("storage").toAbsolutePath().normalize());
                    assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B", "-1B"})
    void evidenceStorageRejectsNonPositiveFileLimits(String size) {
        assertBindingFails(
                StorageBinding.class,
                "proofchain.storage.max-file-size must be greater than zero",
                "proofchain.storage.root=./storage",
                "proofchain.storage.max-file-size=" + size);
    }

    // -------------------------------------------------------------------------------------- Bootstrap

    @Test
    void bootstrapAdministratorIsDisabledAndEmptyByDefault() {
        runner(BootstrapBinding.class).run(context -> {
            assertThat(context).hasNotFailed();
            BootstrapAdminProperties properties = context.getBean(BootstrapAdminProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getUsername()).isNull();
            assertThat(properties.getEmail()).isNull();
            assertThat(properties.getPassword()).isNull();
        });
    }

    // -------------------------------------------------------------------------------------------- CORS

    @Test
    void corsIsDefaultDenyWhenNothingIsConfigured() {
        runner(CorsBinding.class).run(context -> {
            assertThat(context).hasNotFailed();
            CorsProperties properties = context.getBean(CorsProperties.class);
            assertThat(properties.allowedOrigins()).isEmpty();
            assertThat(properties.deniesEveryOrigin()).isTrue();
        });
    }

    @Test
    void corsAcceptsOnlyExplicitOrigins() {
        runner(CorsBinding.class)
                .withPropertyValues("proofchain.cors.allowed-origins=https://console.example.org,http://localhost:4200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CorsProperties properties = context.getBean(CorsProperties.class);
                    assertThat(properties.allowedOrigins())
                            .containsExactly("https://console.example.org", "http://localhost:4200");
                    assertThat(properties.deniesEveryOrigin()).isFalse();
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    *                                       | must not contain a wildcard origin
                    https://*.example.org                   | must not contain a wildcard origin
                    https://ok.example.org,*                | must not contain a wildcard origin
                    ftp://console.example.org               | must start with http:// or https://
                    console.example.org                     | must start with http:// or https://
                    https://console.example.org/admin       | without a path
                    https://                                | without a path
                    """)
    void corsRejectsWildcardAndMalformedOrigins(String origins, String message) {
        assertBindingFails(CorsBinding.class, message, "proofchain.cors.allowed-origins=" + origins);
    }

    @Test
    void corsPropertiesAreImmutableAndNullSafe() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
        assertThat(CorsProperties.denyAll().deniesEveryOrigin()).isTrue();
        assertThat(new CorsProperties(List.of(" https://console.example.org ")).allowedOrigins())
                .containsExactly("https://console.example.org");
    }

    // ------------------------------------------------------------------------------------------ Support

    private static String[] serverLimits() {
        return new String[] {
            "server.shutdown=graceful",
            "server.max-http-request-header-size=16KB",
            "server.tomcat.max-http-form-post-size=256KB",
            "server.tomcat.max-swallow-size=2MB",
            "server.tomcat.max-parameter-count=256",
            "server.tomcat.max-part-count=16",
            "server.tomcat.max-part-header-size=8KB",
            "server.tomcat.connection-timeout=20s",
            "server.tomcat.keep-alive-timeout=20s"
        };
    }

    private static String[] databaseTimeouts() {
        return new String[] {
            "spring.datasource.hikari.connection-timeout=10000",
            "spring.datasource.hikari.validation-timeout=5000",
            "spring.datasource.hikari.initialization-fail-timeout=1",
            "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '10s'"
        };
    }

    private static ApplicationContextRunner runner(Class<?> configuration) {
        return new ApplicationContextRunner().withUserConfiguration(configuration);
    }

    private static void assertBindingFails(Class<?> configuration, String message, String... properties) {
        runner(configuration)
                .withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(message));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PasswordSecurityProperties.class)
    static class PasswordBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MultipartLimitsProperties.class)
    static class MultipartBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HttpRequestLimitsProperties.class)
    static class ServerLimitsBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseTimeoutProperties.class)
    static class DatabaseTimeoutBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GracefulShutdownProperties.class)
    static class ShutdownBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EvidenceStorageProperties.class)
    static class StorageBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BootstrapAdminProperties.class)
    static class BootstrapBinding {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class CorsBinding {}
}
