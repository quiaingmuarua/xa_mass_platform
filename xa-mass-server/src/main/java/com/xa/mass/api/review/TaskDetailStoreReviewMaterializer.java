package com.xa.mass.api.review;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes server review report events into the current TaskDetailStore backing.
 */
public final class TaskDetailStoreReviewMaterializer implements TaskReviewMaterializer {

    private final TaskDetailStore taskDetailStore;

    public TaskDetailStoreReviewMaterializer(TaskDetailStore taskDetailStore) {
        this.taskDetailStore = Objects.requireNonNull(taskDetailStore, "taskDetailStore");
    }

    @Override
    public void apply(TaskReviewReportEvent event) {
        if (event instanceof TaskReviewItemsAcceptedEvent acceptedEvent) {
            applyItemsAccepted(acceptedEvent);
        } else if (event instanceof TaskReviewWorkTerminalEvent terminalEvent) {
            applyWorkTerminal(terminalEvent);
        }
    }

    private void applyItemsAccepted(TaskReviewItemsAcceptedEvent event) {
        String taskId = event.taskId();
        if (isBlank(taskId)) {
            return;
        }
        List<Map<String, Object>> acceptedItems = event.acceptedItems();
        List<String> messageIds = event.messageIds();
        int itemCount = Math.min(acceptedItems.size(), messageIds.size());
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < itemCount; i++) {
            String messageId = messageIds.get(i);
            if (isBlank(messageId)) {
                continue;
            }
            TaskDetailStore.TaskMessageProjection previous =
                    taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null);
            taskDetailStore.upsertTaskMessageProjection(taskId,
                    acceptedProjection(taskId, messageId, acceptedItems.get(i), event.maxRetryCount(), previous, now));
        }
    }

    private TaskDetailStore.TaskMessageProjection acceptedProjection(String taskId,
                                                                     String messageId,
                                                                     Map<String, Object> input,
                                                                     int maxRetryCount,
                                                                     TaskDetailStore.TaskMessageProjection previous,
                                                                     LocalDateTime now) {
        if (previous == null) {
            return new TaskDetailStore.TaskMessageProjection(
                    messageId,
                    taskId,
                    input,
                    null,
                    TaskMessageProjectionStatus.INIT,
                    null,
                    now,
                    now,
                    null,
                    null,
                    0,
                    Math.max(0, maxRetryCount),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                input == null || input.isEmpty() ? previous.input() : input,
                previous.payloadRef(),
                previous.status(),
                previous.assignedTime(),
                firstNonNull(previous.createTime(), now),
                now,
                previous.startTime(),
                previous.completeTime(),
                previous.retryCount(),
                previous.maxRetryCount() > 0 ? previous.maxRetryCount() : Math.max(0, maxRetryCount),
                previous.errorMessage(),
                previous.errorCode(),
                previous.finalReason(),
                previous.output(),
                previous.latestAttemptId(),
                previous.latestAttemptWorkerId(),
                previous.latestAttemptBatchId()
        );
    }

    private void applyWorkTerminal(TaskReviewWorkTerminalEvent event) {
        String taskId = event.taskId();
        String messageId = event.messageId();
        if (isBlank(taskId) || isBlank(messageId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        TaskDetailStore.TaskMessageProjection previous =
                taskDetailStore.getTaskMessageProjection(taskId, messageId).orElse(null);
        LocalDateTime createTime = firstNonNull(asLocalDateTime(event.createTime()),
                previous != null ? previous.createTime() : null);
        LocalDateTime assignedTime = firstNonNull(asLocalDateTime(event.assignedTime()),
                previous != null ? previous.assignedTime() : null);
        LocalDateTime startTime = firstNonNull(asLocalDateTime(event.startTime()),
                previous != null ? previous.startTime() : null);
        LocalDateTime completeTime = firstNonNull(asLocalDateTime(event.completeTime()), now);
        LocalDateTime updateTime = firstNonNull(asLocalDateTime(event.updateTime()), now);
        Map<String, Object> input = previous != null ? previous.input() : eventCodeInput(event.eventCode());
        String latestAttemptId = firstNonBlank(event.attemptId(), previous != null ? previous.latestAttemptId() : null);
        String latestAttemptWorkerId = firstNonBlank(event.workerId(),
                previous != null ? previous.latestAttemptWorkerId() : null);
        String latestAttemptBatchId = firstNonBlank(event.batchId(),
                previous != null ? previous.latestAttemptBatchId() : null);
        taskDetailStore.upsertTaskMessageProjection(taskId, new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                input,
                firstNonBlank(event.payloadRef(), previous != null ? previous.payloadRef() : null),
                parseStatus(event.status()),
                assignedTime,
                createTime,
                updateTime,
                startTime,
                completeTime,
                event.retryCount(),
                resolveMaxRetryCount(event, previous),
                event.errorMessage(),
                event.errorCode(),
                parseFinalReason(event.finalReason()),
                copyMap(event.output()),
                latestAttemptId,
                latestAttemptWorkerId,
                latestAttemptBatchId
        ));
        if (latestAttemptId != null) {
            taskDetailStore.upsertTaskMessageAttemptProjection(taskId, messageId,
                    new TaskDetailStore.TaskMessageAttemptProjection(
                            latestAttemptId,
                            taskId,
                            messageId,
                            event.retryCount() + 1,
                            latestAttemptWorkerId,
                            latestAttemptBatchId,
                            parseAttemptStatus(event.status()),
                            parseAttemptFinalReason(event.finalReason()),
                            event.errorMessage(),
                            event.errorCode(),
                            copyMap(event.output())
                    ));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static TaskMessageProjectionStatus parseStatus(String status) {
        if (isBlank(status)) {
            return TaskMessageProjectionStatus.FAILED;
        }
        return TaskMessageProjectionStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static TaskMessageProjectionFinalReason parseFinalReason(String finalReason) {
        if (isBlank(finalReason)) {
            return null;
        }
        return TaskMessageProjectionFinalReason.valueOf(finalReason.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static TaskMessageAttemptProjectionStatus parseAttemptStatus(String status) {
        if (isBlank(status)) {
            return TaskMessageAttemptProjectionStatus.FAILED;
        }
        return switch (status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCESS" -> TaskMessageAttemptProjectionStatus.SUCCEEDED;
            case "EXPIRED" -> TaskMessageAttemptProjectionStatus.EXPIRED;
            default -> TaskMessageAttemptProjectionStatus.FAILED;
        };
    }

    private static TaskMessageAttemptProjectionFinalReason parseAttemptFinalReason(String finalReason) {
        if (isBlank(finalReason)) {
            return null;
        }
        return switch (finalReason.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BUSINESS_SUCCESS" -> TaskMessageAttemptProjectionFinalReason.SUCCESS;
            case "LEASE_EXPIRED" -> TaskMessageAttemptProjectionFinalReason.LEASE_EXPIRED;
            case "TIMEOUT" -> TaskMessageAttemptProjectionFinalReason.TIMEOUT;
            case "WORKER_LOST" -> TaskMessageAttemptProjectionFinalReason.WORKER_LOST;
            case "MANUAL_CANCELLED" -> TaskMessageAttemptProjectionFinalReason.MANUAL_CANCELLED;
            case "REVOKED_FOR_RETRY" -> TaskMessageAttemptProjectionFinalReason.REVOKED_FOR_RETRY;
            default -> TaskMessageAttemptProjectionFinalReason.BUSINESS_FAILURE;
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return second;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static LocalDateTime asLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static int resolveMaxRetryCount(TaskReviewWorkTerminalEvent event,
                                            TaskDetailStore.TaskMessageProjection previous) {
        if (event.maxRetryCount() > 0 || previous == null) {
            return event.maxRetryCount();
        }
        return previous.maxRetryCount();
    }

    private static Map<String, Object> eventCodeInput(String eventCode) {
        if (isBlank(eventCode)) {
            return null;
        }
        return Map.of("eventCode", eventCode);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
