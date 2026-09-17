package com.xa.mass.server.api.v1.contract.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.workerdelivery.json.Jsons;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record TaskCreateRequest(
        @NotBlank String workerGroupId,
        @Min(0) @Max(99) Integer priority,
        @Min(0) @Max(98) Integer maxRetryTimes,
        @Schema(description = "Optional Pool waterline declarations; omission means no supply. "
                + "Equal targets in the same Group/Pool merge by MAX, never private quotas.")
        @Size(max = 100) List<RefillTarget> refill
) {
    public TaskCreateRequest {
        refill = refill == null ? List.of() : List.copyOf(refill);
        priority = priority == null ? 50 : priority;
        maxRetryTimes = maxRetryTimes == null ? 3 : maxRetryTimes;
    }

    /** Preserve existing scalar binding while distinguishing omitted supply from explicit null. */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static TaskCreateRequest fromJson(
            @JsonProperty("workerGroupId") String workerGroupId,
            @JsonProperty("priority") Integer priority,
            @JsonProperty("maxRetryTimes") Integer maxRetryTimes,
            @JsonProperty("refill") JsonNode refill
    ) {
        if (refill == null) {
            return new TaskCreateRequest(workerGroupId, priority, maxRetryTimes, List.of());
        }
        if (!refill.isArray()) {
            throw new IllegalArgumentException("refill must be a list");
        }
        var declarations = Jsons.parseArray(refill.toString()).stream().map(entry -> {
            if (!(entry instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("invalid refill declaration");
            }
            @SuppressWarnings("unchecked")
            var value = (Map<String, Object>) fields;
            return RefillTarget.parse(value);
        }).toList();
        return new TaskCreateRequest(workerGroupId, priority, maxRetryTimes, declarations);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Task creation field: " + field);
    }
}
