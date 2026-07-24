package it.itsprodigi.proofchain.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI proofChainOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ProofChain API")
                        .version("0.0.1")
                        .description("REST API for managing the chain of custody of digital evidence.")
                        .license(new License().name("MIT")
                                .url("https://opensource.org/license/mit")))
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
