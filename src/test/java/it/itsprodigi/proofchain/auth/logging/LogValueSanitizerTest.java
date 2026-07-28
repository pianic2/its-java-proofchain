package it.itsprodigi.proofchain.auth.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogValueSanitizerTest {

    @Test
    void removesCrLfAndUnsafeControlCharacters() {
        assertThat(LogValueSanitizer.sanitizeUsername("user\r\n\t\u0000name")).isEqualTo("username");
        assertThat(LogValueSanitizer.sanitizePath("/api\r\n\u0007/v1")).isEqualTo("/api/v1");
    }

    @Test
    void truncatesUsernameAndPathToTheirDocumentedLimits() {
        assertThat(LogValueSanitizer.sanitizeUsername("a".repeat(80))).hasSize(64);
        assertThat(LogValueSanitizer.sanitizePath("/" + "a".repeat(600))).hasSize(512);
    }

    @Test
    void representsMissingAndEmailLikeUsernamesSafely() {
        assertThat(LogValueSanitizer.sanitizeUsername(null)).isEqualTo("-");
        assertThat(LogValueSanitizer.sanitizePath(null)).isEqualTo("-");
        assertThat(LogValueSanitizer.sanitizeUsername("operator@example.com")).isEqualTo("-");
    }
}
