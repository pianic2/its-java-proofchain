package it.itsprodigi.proofchain.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Profile contract for the released baseline.
 *
 * <p>Exactly three profiles exist — {@code local} for host execution, {@code container} for Docker Compose execution
 * and {@code test} for automated tests — and every setting that is not an intended difference lives in
 * {@code application.yml}. The first test proves the profile documents declare only the keys they are allowed to
 * override; the remaining tests prove the resolved values.
 */
class ProfileConfigurationTest {

    private static final Set<String> RUNTIME_PROFILE_KEYS = Set.of(
            "spring.config.activate.on-profile",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "proofchain.storage.root");

    private static final Set<String> TEST_PROFILE_KEYS = Set.of(
            "spring.config.activate.on-profile",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "proofchain.jwt.secret",
            "proofchain.jwt.access-token-ttl",
            "proofchain.password.bcrypt-strength",
            "proofchain.storage.root",
            "proofchain.storage.max-file-size");

    @Test
    void theRuntimeProfilesOverrideExactlyTheSameKeys() throws IOException {
        assertThat(declaredKeys("application-local.yml")).isEqualTo(RUNTIME_PROFILE_KEYS);
        assertThat(declaredKeys("application-container.yml")).isEqualTo(RUNTIME_PROFILE_KEYS);
        assertThat(declaredKeys("application-test.yml")).isEqualTo(TEST_PROFILE_KEYS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "container", "test"})
    void everyProfileInheritsTheSharedBaselineUnchanged(String profile) {
        withProfile(profile, environment -> {
            assertThat(environment.getProperty("spring.application.name")).isEqualTo("proofchain");
            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(environment.getProperty("spring.flyway.baseline-on-migrate"))
                    .isEqualTo("false");
            assertThat(environment.getProperty("spring.flyway.validate-on-migrate"))
                    .isEqualTo("true");
            assertThat(environment.getProperty("spring.flyway.out-of-order")).isEqualTo("false");
            assertThat(environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
            assertThat(environment.getProperty("spring.servlet.multipart.max-file-size"))
                    .isEqualTo("50MB");
            assertThat(environment.getProperty("spring.servlet.multipart.max-request-size"))
                    .isEqualTo("51MB");
            assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                    .isEqualTo("30s");
            assertThat(environment.getProperty("server.max-http-request-header-size"))
                    .isEqualTo("16KB");
            assertThat(environment.getProperty("server.tomcat.max-parameter-count"))
                    .isEqualTo("256");
            assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout"))
                    .isEqualTo("10000");
            assertThat(environment.getProperty("spring.datasource.hikari.initialization-fail-timeout"))
                    .isEqualTo("1");
            assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
                    .isEqualTo("SET lock_timeout = '10s'");
            assertThat(environment.getProperty("proofchain.cors.allowed-origins"))
                    .isEmpty();
            assertThat(environment.getProperty("proofchain.bootstrap.admin.enabled"))
                    .isEqualTo("false");
        });
    }

    @Test
    void theLocalProfileTargetsTheHostDatabaseAndTheWorkingDirectoryStorage() {
        withProfile("local", environment -> {
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/proofchain");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("proofchain");
            assertThat(environment.getProperty("proofchain.storage.root")).isEqualTo("./storage");
            assertThat(environment.getProperty("proofchain.password.bcrypt-strength"))
                    .isEqualTo("12");
        });
    }

    @Test
    void theContainerProfileTargetsTheComposeServiceHostAndAContainerStoragePath() {
        withProfile("container", environment -> {
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://postgres:5432/proofchain");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("proofchain");
            assertThat(environment.getProperty("proofchain.storage.root")).isEqualTo("/var/lib/proofchain/storage");
            assertThat(environment.getProperty("proofchain.password.bcrypt-strength"))
                    .isEqualTo("12");
        });
    }

    @Test
    void theTestProfileIsolatesStorageLowersHashingCostAndGeneratesItsOwnSecret() {
        withProfile("test", environment -> {
            assertThat(environment.getProperty("proofchain.password.bcrypt-strength"))
                    .isEqualTo("4");
            assertThat(environment.getProperty("proofchain.storage.root")).contains("proofchain-test-storage-");
            String first = environment.getProperty("proofchain.jwt.secret");
            String second = environment.getProperty("proofchain.jwt.secret");
            assertThat(first).isNotBlank().isNotEqualTo(second);
            assertThat(Base64.getDecoder().decode(first)).hasSizeGreaterThanOrEqualTo(32);
        });
    }

    @Test
    void noProfileDocumentShipsAHardcodedProductionSecret() throws IOException {
        assertThat(declaredValues("application-local.yml").values()).noneMatch(ProfileConfigurationTest::isLiteral);
        assertThat(declaredValues("application-container.yml").values()).noneMatch(ProfileConfigurationTest::isLiteral);
        Object testSecret = declaredValues("application-test.yml").get("proofchain.jwt.secret");
        assertThat(testSecret).asString().isEqualTo("${random.value}${random.value}");
    }

    private static boolean isLiteral(Object value) {
        String text = String.valueOf(value);
        return text.contains("secret") && !text.contains("${");
    }

    private static void withProfile(String profile, Consumer<Environment> assertions) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(profile);
                    assertions.accept(context.getEnvironment());
                });
    }

    private static Set<String> declaredKeys(String resource) throws IOException {
        return declaredValues(resource).keySet();
    }

    private static Map<String, Object> declaredValues(String resource) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
        Map<String, Object> values = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                values.put(name, source.getProperty(name));
            }
        }
        return values;
    }
}
