package com.xa.mass.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI xaMassOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("XA Mass Platform API")
                        .version("v1")
                        .description("Server external API contract for task, project, worker, runtime, and internal debug surfaces."));
    }
}
