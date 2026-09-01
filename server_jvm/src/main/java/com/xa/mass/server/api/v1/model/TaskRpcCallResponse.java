package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRpcCallResponse(
        Map<String, TaskItemResultResponse> results
) {
    public TaskRpcCallResponse {
        Objects.requireNonNull(results, "results");
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    public static TaskRpcCallResponse fromObservedResults(
            List<String> messageIds,
            Map<String, TaskItemResult> observedResults
    ) {
        var items = new LinkedHashMap<String, TaskItemResultResponse>();
        messageIds.forEach(messageId -> {
            items.put(
                    messageId,
                    TaskItemResultResponse.from(
                            observedResults.get(messageId)
                    )
            );
        });
        return new TaskRpcCallResponse(items);
    }
}
