package com.hchat.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档：/api/openapi.json + /api/swagger-ui.html。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hchatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("HChat API")
                        .version("0.1.0")
                        .description("可自托管的聊天基础设施：REST + WebSocket + SSE")
                        .license(new License().name("Apache-2.0")))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
