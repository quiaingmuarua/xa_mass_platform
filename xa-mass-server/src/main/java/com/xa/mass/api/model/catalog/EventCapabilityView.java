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
                description = "Expected response style metadata. Not result-finality behavior.",
                allowableValues = {"NONE", "ACK", "FINAL_RESULT", "STREAM"},
                example = "FINAL_RESULT"
        )
        String responseMode,
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
        @Schema(description = "Workers that declare this event capability")
        List<String> workerIds,
        @Schema(description = "Currently online workers that declare this event capability")
        List<String> onlineWorkerIds,
        @Schema(description = "Whether this event has a direct SDK runtime handler", example = "false")
        boolean hasDirectRuntimeHandler,
        @Schema(description = "Whether at least one online worker declares this event", example = "true")
        boolean hasOnlineWorkerCoverage,
        @Schema(description = "Whether the event has an immediately usable direct handler or online worker coverage", example = "true")
        boolean ready
) {
}
