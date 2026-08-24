package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRpcCallResponse(
        Map<String, TaskRpcCallItemResponse> results
) {
    public TaskRpcCallResponse {
        Objects.requireNonNull(results, "results");
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    public static TaskRpcCallResponse fromObservedResults(
            List<String> messageIds,
            Map<String, String> observedResults
    ) {
        var items = new LinkedHashMap<String, TaskRpcCallItemResponse>();
        messageIds.forEach(messageId -> {
            String payload = observedResults.get(messageId);
            items.put(
                    messageId,
                    payload == null
                            ? TaskRpcCallItemResponse.notObserved()
                            : TaskRpcCallItemResponse.succeeded(payload)
            );
        });
        return new TaskRpcCallResponse(items);
    }
}
