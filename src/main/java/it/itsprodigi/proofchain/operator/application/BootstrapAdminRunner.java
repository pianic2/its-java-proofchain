package it.itsprodigi.proofchain.operator.application;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdminRunner {

    @Bean
    ApplicationRunner bootstrapAdminApplicationRunner(BootstrapAdminService service) {
        return args -> service.bootstrap();
    }
}
