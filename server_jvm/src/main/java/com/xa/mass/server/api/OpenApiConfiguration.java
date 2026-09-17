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
            var values = new io.swagger.v3.oas.models.media.ArraySchema();
            values.setItems(new io.swagger.v3.oas.models.media.StringSchema().minLength(1));
            values.setMinItems(1); values.setMaxItems(100);
            var query = new io.swagger.v3.oas.models.media.MapSchema();
            query.setAdditionalProperties(values); query.setMaxProperties(100);
            query.setDescription("Immutable string-list supply target. The selected Pool maintenance policy interprets fields; "
                    + "empty means no additional condition. No quantity or operator objects.");
            document.getComponents().addSchemas("EligibilityQuery", query);
            Schema<?> target = document.getComponents().getSchemas().get("RefillTarget");
            target.setDescription("Shared Pool supply declaration. Equal normalized targets in a Group/Pool merge by MAX; "
                    + "target is required, including an explicit empty object. This is not a Task-private quota.");
            target.setRequired(List.of("poolName","target","count")); target.setAdditionalProperties(false);
            target.getProperties().get("poolName").setMinLength(1);
            target.getProperties().get("poolName").setPattern(".*\\S.*");
            target.getProperties().put("target", new Schema<>().$ref("#/components/schemas/EligibilityQuery"));
            target.getProperties().get("count").setMinimum(java.math.BigDecimal.ONE);
            target.getProperties().get("count").setMaximum(java.math.BigDecimal.valueOf(1000));
            Schema<?> item = document.getComponents().getSchemas().get("TaskItemRequest");
            Schema<?> workerQuery = document.getComponents().getSchemas().get("WorkerQuery");
            workerQuery.setRequired(List.of("executorName", "input")); workerQuery.setAdditionalProperties(false);
            workerQuery.getProperties().get("executorName").setMinLength(1);
            workerQuery.getProperties().get("executorName").setPattern(".*\\S.*");
            Schema<?> input = workerQuery.getProperties().get("input");
            input.setTypes(new LinkedHashSet<>(List.of("object", "array", "string", "number", "boolean")));
            input.setDescription("Executor-local JSON. Root cannot be null; nested null is preserved. "
                    + "Each container has at most 100 members, container depth at most 8, encoded input at most 64 KiB. "
                    + "Only the selected Matching function interprets its shape.");
            var selector = new Schema<>().$ref("#/components/schemas/WorkerQuery");
            selector.setDescription(item.getProperties().get("workerSelector").getDescription());
            item.getProperties().put("workerSelector", selector);
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
