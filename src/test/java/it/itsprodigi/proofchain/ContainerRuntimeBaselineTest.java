package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Container runtime baseline audit.
 *
 * <p>The security properties of the delivered runtime — non-root, read-only root filesystem, no extra capabilities, no
 * Docker socket, two independent volumes, pinned base images — are decisions, not review comments. A silent edit to
 * {@code Dockerfile} or {@code compose.yml} that removes one of them would otherwise pass every other gate, so the
 * build asserts them directly.
 */
class ContainerRuntimeBaselineTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path COMPOSE = Path.of("compose.yml");
    private static final Pattern PROJECT_VERSION =
            Pattern.compile("<artifactId>proofchain</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    void theImageIsBuiltAndRunOnThePinnedTemurinJava25Bases() throws IOException {
        String dockerfile = dockerfile();
        assertThat(dockerfile).contains("FROM eclipse-temurin:25-jdk AS build");
        assertThat(dockerfile).contains("FROM eclipse-temurin:25-jre AS runtime");
        assertThat(dockerfile)
                .as("the runtime stage must copy the packaged jar, not the build tree")
                .contains("COPY --from=build");
    }

    @Test
    void theApplicationIsBuiltThroughTheRepositoryMavenWrapper() throws IOException {
        assertThat(dockerfile()).contains("./mvnw --batch-mode --no-transfer-progress -DskipTests clean package");
    }

    @Test
    void theRuntimeUserIsADedicatedNonRootIdentityWithAStableNumericPair() throws IOException {
        String dockerfile = dockerfile();
        assertThat(dockerfile).contains("ARG PROOFCHAIN_UID=10001");
        assertThat(dockerfile).contains("ARG PROOFCHAIN_GID=10001");
        assertThat(dockerfile).contains("groupadd --system --gid \"${PROOFCHAIN_GID}\" proofchain");
        assertThat(dockerfile).contains("USER ${PROOFCHAIN_UID}:${PROOFCHAIN_GID}");
        assertThat(compose()).contains("user: \"10001:10001\"");
    }

    @Test
    void theCanonicalContainerStorageRootIsUsedByTheImageAndTheEvidenceVolume() throws IOException {
        assertThat(dockerfile()).contains("PROOFCHAIN_STORAGE_ROOT=/var/lib/proofchain/storage");
        assertThat(compose()).contains("proofchain-evidence-data:/var/lib/proofchain/storage");
    }

    @Test
    void theApplicationServiceRunsReadOnlyWithOnlyTheEvidenceVolumeAndABoundedTmpfs() throws IOException {
        String compose = compose();
        assertThat(compose).contains("read_only: true");
        assertThat(compose).contains("- /tmp:rw,noexec,nosuid,nodev,size=${PROOFCHAIN_TMPFS_SIZE:-256m},mode=1777");
        assertThat(compose)
                .as("the evidence volume is the only durable writable mount")
                .containsOnlyOnce(":/var/lib/proofchain/storage");
    }

    @Test
    void theRuntimeGrantsNoPrivilegeAndNeverMountsTheDockerSocket() throws IOException {
        assertThat(compose()).contains("no-new-privileges:true");
        assertThat(compose()).contains("cap_drop:");
        assertThat(compose()).contains("- ALL");
        assertThat(directives(COMPOSE))
                .doesNotContain("cap_add")
                .doesNotContain("privileged")
                .doesNotContain("docker.sock")
                .doesNotContain("/var/run/docker");
    }

    @Test
    void startupOrderComesFromThePostgresHealthcheckAndNeverFromASleep() throws IOException {
        assertThat(compose()).contains("condition: service_healthy");
        assertThat(compose()).contains("image: postgres:18.4-trixie");
        assertThat(directives(COMPOSE))
                .as("startup order must come from the health condition, never from a delay")
                .doesNotContain("sleep");
        assertThat(directives(COMPOSE))
                .as("the application must stay fail-fast rather than loop on a restart policy")
                .doesNotContain("restart:");
    }

    @Test
    void evidenceAndDatabaseStateUseTwoIndependentNamedVolumes() throws IOException {
        String compose = compose();
        assertThat(compose).contains("proofchain-postgres-data:/var/lib/postgresql");
        assertThat(compose).contains("proofchain-evidence-data:/var/lib/proofchain/storage");
        assertThat(compose.substring(compose.indexOf("\nvolumes:\n")))
                .contains("proofchain-postgres-data:")
                .contains("proofchain-evidence-data:");
    }

    @Test
    void theImageVersionLabelMatchesTheMavenCoordinate() throws IOException {
        Matcher matcher = PROJECT_VERSION.matcher(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
        assertThat(matcher.find()).isTrue();
        assertThat(dockerfile()).contains("org.opencontainers.image.version=\"%s\"".formatted(matcher.group(1)));
    }

    @Test
    void noContainerFileCarriesAnInlineCredential() throws IOException {
        for (Path file : List.of(DOCKERFILE, COMPOSE)) {
            for (String line : directives(file).lines().toList()) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                assertThat(stripped)
                        .as("%s must take every secret from the environment, never from a literal", file)
                        .satisfies(text -> {
                            if (text.contains("PASSWORD") || text.contains("SECRET")) {
                                assertThat(text)
                                        .as("secret-bearing line without a variable reference: %s", text)
                                        .contains("${");
                            }
                        });
            }
        }
    }

    private static String dockerfile() throws IOException {
        return Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
    }

    private static String compose() throws IOException {
        return Files.readString(COMPOSE, StandardCharsets.UTF_8);
    }

    /** The file with every comment line removed, so a rationale comment can never satisfy or break an assertion. */
    private static String directives(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.strip().startsWith("#"))
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
