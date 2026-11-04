package com.fraudguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The text at the top of the generated API description, and the two ways to authenticate.
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI fraudGuardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FraudGuard API")
                        .version("1.0")
                        .description("Real-time fraud detection demo. Runs on synthetic data and is not production-grade: "
                                + "scores and decisions say nothing about real-world accuracy."))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Token from POST /api/auth/login"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key")
                                .description("Service key for ingestion (FRAUDGUARD_INGEST_API_KEYS)")));
    }
}
