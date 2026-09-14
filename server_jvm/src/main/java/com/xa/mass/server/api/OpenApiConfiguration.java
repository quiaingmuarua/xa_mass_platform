package com.xa.mass.server.api;

import io.swagger.v3.oas.models.media.Schema;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashSet;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "XA Mass Runtime API",
                version = "v1",
                description = "Kernel decides scheduling; Server exposes, "
                        + "validates and correlates Runtime APIs; Transport "
                        + "delivers and executes assigned work. HTTP status "
                        + "expresses a coarse request-processing class while "
                        + "ApiErrorResponse.code identifies the business "
                        + "reason. Matched Runtime use cases return 200 when "
                        + "completed, "
                        + "400 for business rejection, 429 for explicit "
                        + "admission capacity and 503 for a temporarily "
                        + "unavailable Owner. Worker Delivery machine routes "
                        + "also use 202 for accepted Reports and 204 for an "
                        + "empty or bodyless successful protocol result.\n\n"
                        + "Diagnostic lookup: [Code Dictionary]"
                        + "(/reference/error-codes) for people and "
                        + "[current-build JSON]"
                        + "(/reference/platform-diagnostic-codes.json) for "
                        + "Agents. `ApiErrorResponse.code` belongs to the "
                        + "`server_jvm` namespace. Adapter and Worker codes "
                        + "use their producer-local namespaces. The "
                        + "dictionary is a current-build lookup; it does not "
                        + "bind API operations to diagnostic codes or promise "
                        + "cross-version compatibility."
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

    @Bean
    OpenApiCustomizer eligibilityQuerySchema() {
        return document -> {
            // Matching's shared query validates itself without depending on HTTP schema annotations.
            Schema<?> query = document.getComponents().getSchemas().get("EligibilityQuery");
            query.setDescription("Operator-free query interpreted by the bound Rule. Empty or omitted query means ANY; "
                    + "workerId lists select identities. Multiple values mean any of those values, not separate quotas.");
            query.setRequired(List.of("count"));
            query.setAdditionalProperties(false);
            query.getProperties().get("count").setMinimum(java.math.BigDecimal.ONE);
            query.getProperties().get("count").setMaximum(java.math.BigDecimal.valueOf(1000));
        };
    }

    @Bean
    OpenApiCustomizer taskItemStateSchema() {
        return document -> {
            // Map value inference drops the DTO's nullable annotation.
            Schema<?> state = document.getComponents().getSchemas().get("TaskItemStateResponse");
            state.setType(null);
            state.setTypes(new LinkedHashSet<>(List.of("object", "null")));
        };
    }
}
