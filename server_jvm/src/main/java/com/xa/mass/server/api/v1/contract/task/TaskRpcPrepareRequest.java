package com.xa.mass.server.api.v1.contract.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.workerdelivery.json.Jsons;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Caller-owned RPC identity; Server stamps attribution from the directory-admitted caller. */
public record TaskRpcPrepareRequest(
        @NotBlank String projectId,
        @NotBlank String workerGroupId,
        @Size(max = 128) String name,
        @io.swagger.v3.oas.annotations.media.Schema(description = "String metadata; server.* keys are reserved. Optional userId must match ^[1-9a-z][0-9a-z]{3,8}$ after trimming.")
        Map<String, String> metadata
) {
    public TaskRpcPrepareRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static TaskRpcPrepareRequest fromJson(
            @JsonProperty("projectId") String projectId,
            @JsonProperty("workerGroupId") String workerGroupId,
            @JsonProperty("name") String name,
            @JsonProperty("metadata") JsonNode metadata) {
        var attributes = new LinkedHashMap<String, String>();
        if (metadata != null && !metadata.isNull()) {
            if (!metadata.isObject()) throw new IllegalArgumentException("metadata must be a string object");
            Jsons.parseObject(metadata.toString()).forEach((key, value) -> {
                if (!(value instanceof String text)) throw new IllegalArgumentException("metadata values must be strings");
                attributes.put(key, text);
            });
        }
        return new TaskRpcPrepareRequest(projectId, workerGroupId, name, attributes);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported RPC Task preparation field: " + field);
    }
}
