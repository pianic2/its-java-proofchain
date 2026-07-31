package it.itsprodigi.proofchain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.itsprodigi.proofchain.auth.application.JwtProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The JWT contract now lives in the bound properties record itself, so the same rejections that used to happen in a
 * factory method now happen while configuration is bound and therefore fail the application context.
 */
class JwtConfigurationTest {
    private final JwtConfig config = new JwtConfig();
    private final String valid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void validatesSecretAndTtl() {
        assertThatThrownBy(() -> new JwtProperties("%%%", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new JwtProperties(valid, Duration.ofMinutes(1)).accessTokenTtl())
                .isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> new JwtProperties(valid, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(valid, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesTheSigningKeyFromTheConfiguredSecret() {
        assertThat(config.jwtSigningKey(new JwtProperties(valid, Duration.ofMinutes(1)))
                        .getEncoded())
                .hasSize(JwtProperties.MINIMUM_SECRET_BYTES);
    }

    @Test
    void createsProductionClock() {
        assertThat(config.jwtClock()).isNotNull();
    }
}
