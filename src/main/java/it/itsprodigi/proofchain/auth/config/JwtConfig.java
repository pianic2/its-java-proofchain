package it.itsprodigi.proofchain.auth.config;

import io.jsonwebtoken.security.Keys;
import it.itsprodigi.proofchain.auth.application.JwtProperties;
import java.time.Clock;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {
    public static final String ISSUER = "proofchain-api";

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return Keys.hmacShaKeyFor(properties.decodedSecret());
    }

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }
}
