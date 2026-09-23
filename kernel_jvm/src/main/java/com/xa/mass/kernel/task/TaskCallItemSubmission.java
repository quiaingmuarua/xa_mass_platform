package com.xa.mass.kernel.task;

import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface TaskCallItemSubmission {

    int MAX_ITEMS = 100;

    TaskCallSubmissionResult submit(
            String taskId,
            List<TaskItem> items
    );

    enum TaskCallSubmissionStatus {
        SUBMITTED("submitted"),
        NOT_FOUND("not_found"),
        CLOSED("closed"),
        STALE("stale"),
        INVALID("invalid"),
        RETRYABLE("retryable");

        private final String wireValue;

        TaskCallSubmissionStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static TaskCallSubmissionStatus fromWireValue(String value) {
            for (TaskCallSubmissionStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException(
                    "unknown Task Call submission status"
            );
        }
    }

    record TaskCallSubmissionResult(
            TaskCallSubmissionStatus status,
            Map<String, TaskItemAppendResult> itemResults,
            @Nullable String reason
    ) {
        public TaskCallSubmissionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(itemResults, "itemResults");
            itemResults = Collections.unmodifiableMap(
                    new LinkedHashMap<>(itemResults)
            );
        }
    }
}
