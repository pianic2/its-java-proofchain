package it.itsprodigi.proofchain.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * Published API version. It is deliberately identical to the Maven project version so the released artifact and the
     * documented contract can never drift apart; a test asserts the equality.
     */
    public static final String API_VERSION = "1.0.0";

    @Bean
    OpenAPI proofChainOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ProofChain API")
                        .version(API_VERSION)
                        .description("REST API for managing the chain of custody of digital evidence.")
                        .license(new License().name("MIT").url("https://opensource.org/license/mit")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
