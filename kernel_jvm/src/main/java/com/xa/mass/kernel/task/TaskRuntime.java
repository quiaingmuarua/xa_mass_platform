package com.xa.mass.kernel.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface TaskRuntime {

    int MAX_SUCCESS_RESULT_SCAN_COUNT_HINT = 1000;

    Set<String> CONFIG_KEYS = Set.of(
            "priority",
            "maximumCandidateWorkers",
            "maxRetryTimes"
    );

    TaskCreationResult createTask(TaskDescriptor descriptor);

    Map<String, TaskItemAppendResult> appendItems(
            String taskId,
            List<TaskItem> items
    );

    Map<String, @Nullable TaskItem> loadTaskItems(
            String taskId,
            List<String> messageIds
    );

    void storeTaskItemSuccessResults(
            String taskId,
            Map<String, String> results
    );

    Map<String, @Nullable String> loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    );

    TaskItemSuccessResultPage scanTaskItemSuccessResults(
            String taskId,
            String cursor,
            int countHint
    );

    enum WorkerAllocationMechanism {
        PRECOMPUTED_TASK_RULE,
        DIRECT_ITEM_RULE
    }

    enum TaskIdleDisposition {
        CLOSE_WHEN_IDLE,
        PARK_WHEN_IDLE
    }

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

    enum TaskCreationStatus {
        CREATED("created"),
        RETRYABLE("retryable"),
        CONFLICT("conflict"),
        INVALID("invalid");

        private final String wireValue;

        TaskCreationStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record TaskItem(
            String messageId,
            String eventCode,
            long createdAtMillis,
            Map<String, Object> payload,
            int priority,
            @Nullable Long expireAtMillis,
            @Nullable Map<String, Object> allocationRule
    ) {
        public TaskItem {
            requireNonBlank(messageId, "messageId");
            requireNonBlank(eventCode, "eventCode");
            Objects.requireNonNull(payload, "payload");
            if (priority < 0 || priority > 10) {
                throw new IllegalArgumentException(
                        "priority must be in 0..10"
                );
            }
            if (createdAtMillis < 0) {
                throw new IllegalArgumentException(
                        "createdAtMillis must be non-negative"
                );
            }
            if (expireAtMillis != null
                    && expireAtMillis <= createdAtMillis) {
                throw new IllegalArgumentException(
                        "expireAtMillis must be after createdAtMillis"
                );
            }
            payload = immutableObjectMap(payload);
            allocationRule = allocationRule == null
                    ? null
                    : immutableObjectMap(allocationRule);
        }
    }

    record TaskDescriptor(
            String taskId,
            String workerGroupId,
            WorkerAllocationMechanism workerAllocationMechanism,
            TaskIdleDisposition idleDisposition,
            @Nullable Map<String, Object> allocationRule,
            Map<String, String> config
    ) {
        public TaskDescriptor {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(workerGroupId, "workerGroupId");
            Objects.requireNonNull(
                    workerAllocationMechanism,
                    "workerAllocationMechanism"
            );
            Objects.requireNonNull(idleDisposition, "idleDisposition");
            Objects.requireNonNull(config, "config");
            if (workerAllocationMechanism
                    == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                    && allocationRule == null) {
                throw new IllegalArgumentException(
                        "PRECOMPUTED_TASK_RULE requires allocationRule"
                );
            }
            if (workerAllocationMechanism
                    == WorkerAllocationMechanism.DIRECT_ITEM_RULE
                    && allocationRule != null) {
                throw new IllegalArgumentException(
                        "DIRECT_ITEM_RULE forbids allocationRule"
                );
            }
            if (!config.keySet().equals(CONFIG_KEYS)) {
                throw new IllegalArgumentException(
                        "config must contain exactly the declared keys"
                );
            }
            int priority = decimalConfig(config, "priority");
            int maximumCandidates = decimalConfig(
                    config,
                    "maximumCandidateWorkers"
            );
            int maxRetryTimes = decimalConfig(config, "maxRetryTimes");
            if (priority < 0 || priority > 99) {
                throw new IllegalArgumentException(
                        "Task priority must be in 0..99"
                );
            }
            if (maximumCandidates <= 0) {
                throw new IllegalArgumentException(
                        "maximumCandidateWorkers must be positive"
                );
            }
            if (maxRetryTimes < 0 || maxRetryTimes > 98) {
                throw new IllegalArgumentException(
                        "maxRetryTimes must be in 0..98"
                );
            }
            allocationRule = allocationRule == null
                    ? null
                    : immutableObjectMap(allocationRule);
            config = Collections.unmodifiableMap(
                    new LinkedHashMap<>(config)
            );
        }
    }

    record TaskItemAppendResult(
            TaskItemAppendStatus status,
            @Nullable String reason
    ) {
        public TaskItemAppendResult {
            Objects.requireNonNull(status, "status");
        }

        public TaskItemAppendResult(TaskItemAppendStatus status) {
            this(status, null);
        }
    }

    record TaskItemSuccessResultPage(
            String nextCursor,
            Map<String, String> results
    ) {
        public TaskItemSuccessResultPage {
            requireDecimal(nextCursor, "nextCursor");
            Objects.requireNonNull(results, "results");
            results.forEach((messageId, payload) -> {
                requireNonBlank(messageId, "result messageId");
                requireNonBlank(payload, "result payload");
            });
            results = Collections.unmodifiableMap(
                    new LinkedHashMap<>(results)
            );
        }
    }

    record TaskCreationResult(
            TaskCreationStatus status,
            @Nullable String reason
    ) {
        public TaskCreationResult {
            Objects.requireNonNull(status, "status");
        }

        public TaskCreationResult(TaskCreationStatus status) {
            this(status, null);
        }
    }

    private static int decimalConfig(
            Map<String, String> config,
            String key
    ) {
        String value = config.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "config " + key + " must be decimal text"
            );
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        "config " + key + " must be decimal text"
                );
            }
        }
        return Integer.parseInt(value);
    }

    private static Map<String, Object> immutableObjectMap(
            Map<String, Object> source
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }

    private static void requireDecimal(String value, String name) {
        requireNonBlank(value, name);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        name + " must be decimal text"
                );
            }
        }
    }
}
