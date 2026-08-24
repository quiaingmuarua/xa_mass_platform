package com.xa.mass.server.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "XA Mass Runtime API",
                version = "v1",
                description = "Kernel decides scheduling; Server exposes, "
                        + "validates and correlates Runtime APIs; Transport "
                        + "delivers and executes assigned work."
        ),
        tags = {
                @Tag(
                        name = ApiTags.WORKER_RESOURCES,
                        description = "Declare WorkerGroups and prepare or "
                                + "control Worker resources."
                ),
                @Tag(
                        name = ApiTags.TASKS,
                        description = "Create and control Tasks, append "
                                + "Items, load or export Results and call "
                                + "managed Tasks by Task ID."
                ),
                @Tag(
                        name = ApiTags.RUNTIME_VIEW,
                        description = "Read-only bounded runtime projections "
                                + "without total or lifecycle claims."
                ),
                @Tag(
                        name = ApiTags.WORKER_DELIVERY,
                        description = "Worker and Adapter delivery boundaries "
                                + "plus best-effort Direct Call."
                )
        }
)
public class OpenApiConfiguration {
}
