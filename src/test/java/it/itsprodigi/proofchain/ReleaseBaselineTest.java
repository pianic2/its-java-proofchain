package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;

import it.itsprodigi.proofchain.common.config.OpenApiConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Release baseline audit.
 *
 * <p>The frozen artifact is {@code 1.0.0} on Java 25 with Spring Boot 4.0.7 and the Maven Wrapper. These assertions
 * make the version a build-verified fact rather than a manual step, and they catch any file still referring to the
 * retired snapshot coordinate.
 */
class ReleaseBaselineTest {

    private static final Pattern PROJECT_VERSION =
            Pattern.compile("<artifactId>proofchain</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern PARENT_VERSION =
            Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern JAVA_VERSION = Pattern.compile("<java\\.version>([^<]+)</java\\.version>");

    @Test
    void theMavenProjectIsFrozenAtTheReleaseVersion() throws IOException {
        assertThat(capture(PROJECT_VERSION)).isEqualTo("1.0.0");
    }

    @Test
    void theCertifiedBuildBaselineIsUnchanged() throws IOException {
        assertThat(capture(PARENT_VERSION)).isEqualTo("4.0.7");
        assertThat(capture(JAVA_VERSION)).isEqualTo("25");
        assertThat(Files.readString(Path.of(".mvn", "wrapper", "maven-wrapper.properties"), StandardCharsets.UTF_8))
                .contains("apache-maven-3.9.9");
    }

    @Test
    void thePublishedApiVersionMatchesTheProjectVersion() throws IOException {
        assertThat(OpenApiConfig.API_VERSION).isEqualTo(capture(PROJECT_VERSION));
    }

    @Test
    void noTrackedFileStillReferencesTheRetiredSnapshotVersion() throws IOException {
        // Assembled at run time so this audit never matches its own source file.
        String retired = "0.0.1" + "-SNAPSHOT";
        List<Path> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("pom.xml"), Path.of("src"), Path.of("docs"), Path.of("README.md"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        if (Files.readString(file, StandardCharsets.UTF_8).contains(retired)) {
                            offenders.add(file);
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // Binary or unreadable fixtures cannot carry a Maven coordinate.
                    }
                });
            }
        }
        assertThat(offenders)
                .as("files still referencing the retired snapshot coordinate")
                .isEmpty();
    }

    private static String capture(Pattern pattern) throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(pom);
        assertThat(matcher.find())
                .as("pattern %s must match the project descriptor", pattern)
                .isTrue();
        return matcher.group(1);
    }
}
