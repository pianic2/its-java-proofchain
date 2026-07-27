package it.itsprodigi.proofchain.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtConfigurationTest {
    private final JwtConfig config = new JwtConfig();
    private final String valid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void validatesSecretAndTtl() {
        assertThatThrownBy(() -> config.jwtProperties("", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtProperties("%%%", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> config.jwtProperties("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(config.jwtProperties(valid, Duration.ofMinutes(1)).accessTokenTtl())
                .isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> config.jwtProperties(valid, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.jwtProperties(valid, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsProductionClock() {
        assertThat(config.jwtClock()).isNotNull();
    }
}
