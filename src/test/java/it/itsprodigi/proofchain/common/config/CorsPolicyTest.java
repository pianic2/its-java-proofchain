package it.itsprodigi.proofchain.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Cross-origin policy contract.
 *
 * <p>With the frozen empty allowlist the policy resolves to no configuration at all, which is what makes the API
 * default-deny: the response carries no {@code Access-Control-Allow-Origin} header for any request. A widened allowlist
 * can only ever contain explicit origins, never a wildcard, and it never enables credentials.
 */
class CorsPolicyTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void theDefaultPolicyResolvesToNoCorsConfiguration() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource(CorsProperties.denyAll());

        assertThat(source.getCorsConfiguration(request("GET", "/api/v1/cases"))).isNull();
        assertThat(source.getCorsConfiguration(request("OPTIONS", "/api/v1/auth/login")))
                .isNull();
    }

    @Test
    void anExplicitAllowlistNeverEnablesWildcardsOrCredentials() {
        CorsConfigurationSource source =
                securityConfig.corsConfigurationSource(new CorsProperties(List.of("https://console.example.org")));

        CorsConfiguration configuration = source.getCorsConfiguration(request("GET", "/api/v1/cases"));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://console.example.org");
        assertThat(configuration.getAllowedOriginPatterns()).isNull();
        assertThat(configuration.getAllowCredentials()).isFalse();
        assertThat(configuration.getAllowedMethods()).doesNotContain(CorsConfiguration.ALL);
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type");
        assertThat(configuration.checkOrigin("https://attacker.example.org")).isNull();
        assertThat(configuration.checkOrigin("https://console.example.org")).isEqualTo("https://console.example.org");
    }

    private static HttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
