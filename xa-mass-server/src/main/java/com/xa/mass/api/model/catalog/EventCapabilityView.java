package com.xa.mass.api.model.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(
        name = "EventCapabilityView",
        description = "Catalog event capability read view. Metadata fields are descriptive and do not change runtime behavior."
)
public record EventCapabilityView(
        @Schema(description = "Global event/capability code", example = "crawler.fetch-page")
        String eventCode,
        @Schema(description = "Display name for the event", example = "Crawler Fetch Page")
        String eventName,
        @Schema(description = "Whether the event definition is enabled", example = "true")
        boolean enabled,
        @Schema(
                description = "Descriptive priority metadata. Not a queue-placement decision.",
                allowableValues = {"CONTROL", "INTERACTIVE", "STANDARD", "BULK"},
                example = "STANDARD"
        )
        String priorityClass,
        @Schema(
                description = "Compatibility response summary metadata. Not result-finality behavior.",
                allowableValues = {"NONE", "ACK", "FINAL_RESULT", "STREAM"},
                example = "FINAL_RESULT"
        )
        String responseMode,
        @Schema(
                description = "Descriptive delivery acknowledgement expectation. Not command or task-result behavior.",
                allowableValues = {"NONE", "HANDLER_ACCEPTED", "DELIVERY_ACCEPTED"},
                example = "NONE"
        )
        String deliveryAcknowledgementMode,
        @Schema(
                description = "Descriptive convergence expectation. Not a lifecycle owner decision.",
                allowableValues = {"NONE", "FINAL_RESULT", "STREAM"},
                example = "FINAL_RESULT"
        )
        String convergenceMode,
        @Schema(
                description = "Target owner hint metadata. Not a worker command or state-report route.",
                allowableValues = {"WORKER", "TASK_ENGINE", "OPERATOR", "WORKER_MANAGER"},
                example = "WORKER"
        )
        String targetScope,
        @Schema(
                description = "Invocation model derived from task modes and direct SDK handlers.",
                allowableValues = {"TASK_BACKED", "DIRECT_RUNTIME"},
                example = "TASK_BACKED"
        )
        String invocationModel,
        @Schema(description = "Project codes that expose this event")
        List<String> projectCodes,
        @Schema(description = "Workers declared in WorkerGroups that expose this event capability")
        List<String> declaredWorkerIds,
        @Schema(description = "Currently reachable workers that declare this event capability")
        List<String> reachableWorkerIds,
        @Schema(description = "Whether this event has a direct SDK runtime handler", example = "false")
        boolean hasDirectRuntimeHandler,
        @Schema(description = "Whether at least one reachable worker declares this event", example = "true")
        boolean hasReachableWorkerCoverage,
        @Schema(description = "Whether the catalog has direct-runtime or reachable-worker invocation coverage; not scheduling eligibility", example = "true")
        boolean hasInvocationCoverage
) {
}
