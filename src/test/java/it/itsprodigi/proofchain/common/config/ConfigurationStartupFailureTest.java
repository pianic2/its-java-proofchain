package it.itsprodigi.proofchain.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.auth.application.JwtProperties;
import it.itsprodigi.proofchain.auth.config.JwtConfig;
import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Fail-closed startup contract.
 *
 * <p>Every case asserts that the application context itself fails to refresh. A logged warning, a fallback value or an
 * auto-generated secret would all make these assertions fail, which is exactly the regression they exist to prevent.
 */
class ConfigurationStartupFailureTest {

    private static final String STRONG_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void aCompleteJwtConfigurationStartsAndProducesASigningKey() {
        jwtRunner()
                .withPropertyValues("proofchain.jwt.secret=" + STRONG_SECRET, "proofchain.jwt.access-token-ttl=PT30M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecretKey.class);
                    assertThat(context.getBean(JwtProperties.class).decodedSecret())
                            .hasSize(JwtProperties.MINIMUM_SECRET_BYTES);
                });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                    ''                                 | PT30M  | proofchain.jwt.secret must be provided
                    'this-is-not-base-64'              | PT30M  | must be standard Base64
                    'c2hvcnQtc2VjcmV0'                 | PT30M  | must decode to at least 32 bytes
                    'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=' | PT0S | access-token-ttl must be positive
                    """)
    void theContextRefusesToStartWithoutAStrongSecretAndAPositiveTtl(String secret, String ttl, String message) {
        jwtRunner()
                .withPropertyValues("proofchain.jwt.secret=" + secret, "proofchain.jwt.access-token-ttl=" + ttl)
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(message));
    }

    @Test
    void theContextRefusesToStartWhenNoJwtSecretIsSuppliedAtAll() {
        jwtRunner().withPropertyValues("proofchain.jwt.access-token-ttl=PT30M").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("proofchain.jwt.secret must be provided"));
    }

    @Test
    void aUsableStorageRootStartsTheStorageAdapter(@TempDir Path root) {
        storageRunner(root.resolve("evidence"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(EvidenceStoragePort.class));
    }

    @Test
    void theContextRefusesToStartWhenTheStorageRootIsAFile(@TempDir Path root) throws IOException {
        Path file = Files.createFile(root.resolve("not-a-directory"));

        storageRunner(file).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("Evidence storage location is not a directory"));
    }

    @Test
    void theContextRefusesToStartWhenTheStorageRootIsASymbolicLink(@TempDir Path root) throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path link = Files.createSymbolicLink(root.resolve("link"), target);

        storageRunner(link).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasStackTraceContaining("UnsafeEvidenceStoragePathException"));
    }

    @Test
    void theContextRefusesToStartWhenARuntimeProfileHasNoDatasourceCredentials() {
        datasourceRunner()
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/proofchain",
                        "spring.datasource.username=proofchain",
                        "spring.datasource.password=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("spring.datasource.password must be provided"));
    }

    @Test
    void theTestProfileDoesNotRequireRuntimeDatasourceCredentials() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=test")
                .withUserConfiguration(RuntimeConfigurationValidation.RuntimeDatasourceValidation.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RuntimeDatasourceProperties.class);
                });
    }

    private static ApplicationContextRunner jwtRunner() {
        return new ApplicationContextRunner().withUserConfiguration(JwtConfig.class);
    }

    private static ApplicationContextRunner storageRunner(Path root) {
        return new ApplicationContextRunner()
                .withUserConfiguration(EvidenceStorageConfiguration.class)
                .withPropertyValues("proofchain.storage.root=" + root, "proofchain.storage.max-file-size=50MB");
    }

    private static ApplicationContextRunner datasourceRunner() {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local")
                .withUserConfiguration(RuntimeConfigurationValidation.RuntimeDatasourceValidation.class);
    }
}
