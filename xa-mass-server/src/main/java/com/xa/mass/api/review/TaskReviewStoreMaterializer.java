package com.xa.mass.api.review;

import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Materializes server review report events into the server-local review store.
 */
public final class TaskReviewStoreMaterializer implements TaskReviewMaterializer {

    private final TaskReviewStore store;

    public TaskReviewStoreMaterializer(TaskReviewStore store) {
        this.store = Objects.requireNonNull(store, "store");
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
            TaskReviewItem previous = store.findItem(taskId, messageId).orElse(null);
            store.upsertItem(taskId, acceptedItem(taskId, messageId, acceptedItems.get(i),
                    event.maxRetryCount(), previous, now));
        }
    }

    private TaskReviewItem acceptedItem(String taskId,
                                        String messageId,
                                        Map<String, Object> input,
                                        int maxRetryCount,
                                        TaskReviewItem previous,
                                        LocalDateTime now) {
        if (previous == null) {
            return new TaskReviewItem(
                    messageId,
                    resolveEventCode(input),
                    "INIT",
                    null,
                    null,
                    0,
                    Math.max(0, maxRetryCount),
                    now,
                    null,
                    null,
                    null,
                    now,
                    input,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        Map<String, Object> nextInput = input == null || input.isEmpty() ? previous.input() : input;
        return new TaskReviewItem(
                messageId,
                firstNonBlank(resolveEventCode(nextInput), previous.eventCode()),
                previous.status(),
                previous.finalReason(),
                previous.payloadRef(),
                previous.retryCount(),
                previous.maxRetryCount() > 0 ? previous.maxRetryCount() : Math.max(0, maxRetryCount),
                firstNonNull(previous.createTime(), now),
                previous.assignedTime(),
                previous.startTime(),
                previous.completeTime(),
                now,
                nextInput,
                previous.workerId(),
                previous.batchId(),
                previous.attemptId(),
                previous.errorCode(),
                previous.errorMessage(),
                previous.output()
        );
    }

    private void applyWorkTerminal(TaskReviewWorkTerminalEvent event) {
        String taskId = event.taskId();
        String messageId = event.messageId();
        if (isBlank(taskId) || isBlank(messageId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        TaskReviewItem previous = store.findItem(taskId, messageId).orElse(null);
        LocalDateTime createTime = firstNonNull(asLocalDateTime(event.createTime()),
                previous != null ? previous.createTime() : null);
        LocalDateTime assignedTime = firstNonNull(asLocalDateTime(event.assignedTime()),
                previous != null ? previous.assignedTime() : null);
        LocalDateTime startTime = firstNonNull(asLocalDateTime(event.startTime()),
                previous != null ? previous.startTime() : null);
        LocalDateTime completeTime = firstNonNull(asLocalDateTime(event.completeTime()), now);
        LocalDateTime updateTime = firstNonNull(asLocalDateTime(event.updateTime()), now);
        Map<String, Object> input = previous != null ? previous.input() : eventCodeInput(event.eventCode());
        String attemptId = firstNonBlank(event.attemptId(), previous != null ? previous.attemptId() : null);
        String workerId = firstNonBlank(event.workerId(), previous != null ? previous.workerId() : null);
        String batchId = firstNonBlank(event.batchId(), previous != null ? previous.batchId() : null);
        store.upsertItem(taskId, new TaskReviewItem(
                messageId,
                firstNonBlank(event.eventCode(), previous != null ? previous.eventCode() : null),
                normalizeStatus(event.status(), "FAILED"),
                event.finalReason(),
                firstNonBlank(event.payloadRef(), previous != null ? previous.payloadRef() : null),
                event.retryCount(),
                resolveMaxRetryCount(event, previous),
                createTime,
                assignedTime,
                startTime,
                completeTime,
                updateTime,
                input,
                workerId,
                batchId,
                attemptId,
                event.errorCode(),
                event.errorMessage(),
                copyMap(event.output())
        ));
        if (attemptId != null) {
            store.upsertAttempt(taskId, messageId, new TaskReviewAttempt(
                    attemptId,
                    taskId,
                    messageId,
                    event.retryCount() + 1,
                    workerId,
                    batchId,
                    normalizeAttemptStatus(event.status()),
                    normalizeAttemptFinalReason(event.finalReason()),
                    event.errorCode(),
                    event.errorMessage(),
                    copyMap(event.output())
            ));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeStatus(String status, String fallback) {
        return isBlank(status) ? fallback : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalizeAttemptStatus(String status) {
        String normalized = normalizeStatus(status, "FAILED");
        return switch (normalized) {
            case "SUCCESS" -> "SUCCEEDED";
            case "EXPIRED" -> "EXPIRED";
            default -> "FAILED";
        };
    }

    private static String normalizeAttemptFinalReason(String finalReason) {
        if (isBlank(finalReason)) {
            return null;
        }
        return switch (finalReason.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "BUSINESS_SUCCESS" -> "SUCCESS";
            case "LEASE_EXPIRED" -> "LEASE_EXPIRED";
            case "TIMEOUT" -> "TIMEOUT";
            case "WORKER_LOST" -> "WORKER_LOST";
            case "MANUAL_CANCELLED" -> "MANUAL_CANCELLED";
            case "REVOKED_FOR_RETRY" -> "REVOKED_FOR_RETRY";
            default -> "BUSINESS_FAILURE";
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

    private static int resolveMaxRetryCount(TaskReviewWorkTerminalEvent event, TaskReviewItem previous) {
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

    private static String resolveEventCode(Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        Object rawValue = input.get("eventCode");
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
