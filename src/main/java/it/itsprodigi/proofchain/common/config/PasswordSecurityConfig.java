package it.itsprodigi.proofchain.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableConfigurationProperties(PasswordSecurityProperties.class)
public class PasswordSecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder(PasswordSecurityProperties properties) {
        properties.validate();
        return new BCryptPasswordEncoder(properties.getBcryptStrength());
    }
}
