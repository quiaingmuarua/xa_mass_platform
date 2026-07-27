package com.xa.mass.server.taskdata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface TaskDataRuntime {

    Map<String, TaskItemAppendResult> appendTaskItems(
            String taskId,
            List<TaskItemRecord> items
    );

    Map<String, String> loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    );

    enum TaskItemAppendStatus {
        APPENDED("appended"),
        RETRYABLE("retryable"),
        NOT_FOUND("not_found"),
        INVALID("invalid");

        private final String wireValue;

        TaskItemAppendStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record TaskItemAppendResult(
            TaskItemAppendStatus status,
            String reason
    ) {
        public TaskItemAppendResult {
            Objects.requireNonNull(status, "status");
        }

        public TaskItemAppendResult(TaskItemAppendStatus status) {
            this(status, null);
        }
    }

    record TaskItemRecord(
            String messageId,
            String eventCode,
            long createdAtMillis,
            Map<String, Object> payload,
            int priority,
            Long expireAtMillis,
            Map<String, Object> allocationRule
    ) {
        public TaskItemRecord {
            if (payload != null) {
                payload = immutableMap(payload);
            }
            if (allocationRule != null) {
                allocationRule = immutableMap(allocationRule);
            }
        }

        private static Map<String, Object> immutableMap(
                Map<String, Object> source
        ) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
