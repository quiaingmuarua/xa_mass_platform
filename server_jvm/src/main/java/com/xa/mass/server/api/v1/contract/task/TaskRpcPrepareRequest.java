package com.xa.mass.server.api.v1.contract.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.workerdelivery.json.Jsons;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Administrator request to create or reuse a Server-minted RPC channel for
 * {@code (projectId, ownerUserId, workerGroupId)}.
 */
public record TaskRpcPrepareRequest(
        @NotBlank String projectId,
        @NotBlank String workerGroupId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional directory userId that owns and calls this RPC channel. Defaults to the authenticated administrator.")
        @Size(max = 32) @Nullable String ownerUserId,
        @Size(max = 128) @Nullable String name,
        @io.swagger.v3.oas.annotations.media.Schema(description = "String metadata; userId and server.* keys are reserved. Server stamps the admitted owner.")
        Map<String, String> metadata,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional explicit Pool supply. Omission uses the host Group RPC default; [] means no supply. Explicit null is invalid. Saved supply is immutable.")
        @Size(max = 100) @Nullable List<RefillTarget> refill
) {
    public TaskRpcPrepareRequest {
        ownerUserId = ownerUserId == null || ownerUserId.isBlank() ? null : ownerUserId.strip();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (refill != null) {
            if (refill.size() > 100) throw new IllegalArgumentException("refill must contain at most 100 entries");
            refill = List.copyOf(refill);
        }
    }

    public TaskRpcPrepareRequest(
            String projectId,
            String workerGroupId,
            @Nullable String name,
            Map<String, String> metadata,
            @Nullable List<RefillTarget> refill) {
        this(projectId, workerGroupId, null, name, metadata, refill);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static TaskRpcPrepareRequest fromJson(
            @JsonProperty("projectId") String projectId,
            @JsonProperty("workerGroupId") String workerGroupId,
            @JsonProperty("ownerUserId") @Nullable String ownerUserId,
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("metadata") @Nullable JsonNode metadata,
            @JsonProperty("refill") @Nullable JsonNode refill) {
        var attributes = new LinkedHashMap<String, String>();
        if (metadata != null && !metadata.isNull()) {
            if (!metadata.isObject()) throw new IllegalArgumentException("metadata must be a string object");
            Jsons.parseObject(metadata.toString()).forEach((key, value) -> {
                if (!(value instanceof String text)) throw new IllegalArgumentException("metadata values must be strings");
                attributes.put(key, text);
            });
        }
        List<RefillTarget> declarations = null;
        if (refill != null) {
            if (!refill.isArray()) throw new IllegalArgumentException("refill must be a list");
            declarations = Jsons.parseArray(refill.toString()).stream().map(entry -> {
                if (!(entry instanceof Map<?, ?> fields)) throw new IllegalArgumentException("invalid refill declaration");
                @SuppressWarnings("unchecked") var value = (Map<String, Object>) fields;
                return RefillTarget.parse(value);
            }).toList();
        }
        return new TaskRpcPrepareRequest(projectId, workerGroupId, ownerUserId, name, attributes, declarations);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported RPC Task preparation field: " + field);
    }
}
