package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemResultResponse(
        TaskItemResultStatus status,
        @Nullable String opaqueResultPayload
) {
    public TaskItemResultResponse {
        Objects.requireNonNull(status, "status");
        if (status == TaskItemResultStatus.SUCCEEDED) {
            Objects.requireNonNull(
                    opaqueResultPayload,
                    "opaqueResultPayload"
            );
        } else if (opaqueResultPayload != null) {
            throw new IllegalArgumentException(
                    "only succeeded Results may contain a payload"
            );
        }
    }

    public static TaskItemResultResponse from(
            @Nullable TaskItemResult result
    ) {
        if (result == null) {
            return notObserved();
        }
        if (!result.succeeded()) {
            return failed();
        }
        return succeeded(Objects.requireNonNull(
                result.opaqueResultPayload(),
                "opaqueResultPayload"
        ));
    }

    public static TaskItemResultResponse succeeded(String payload) {
        return new TaskItemResultResponse(
                TaskItemResultStatus.SUCCEEDED,
                payload
        );
    }

    public static TaskItemResultResponse failed() {
        return new TaskItemResultResponse(
                TaskItemResultStatus.FAILED,
                null
        );
    }

    public static TaskItemResultResponse notObserved() {
        return new TaskItemResultResponse(
                TaskItemResultStatus.NOT_OBSERVED,
                null
        );
    }
}
