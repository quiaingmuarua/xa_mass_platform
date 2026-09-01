package com.xa.mass.server.api.v1.contract.task;

import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskItemResultsLoadResponse(
        Map<String, TaskItemResultResponse> results
) {
    public TaskItemResultsLoadResponse {
        Objects.requireNonNull(results, "results");
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }

    public static TaskItemResultsLoadResponse fromObservedResults(
            Iterable<String> messageIds,
            Map<String, TaskItemResult> observedResults
    ) {
        var results = new LinkedHashMap<String, TaskItemResultResponse>();
        messageIds.forEach(messageId -> results.put(
                messageId,
                TaskItemResultResponse.from(observedResults.get(messageId))
        ));
        return new TaskItemResultsLoadResponse(results);
    }
}
