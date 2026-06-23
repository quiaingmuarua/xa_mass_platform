package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerAction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record WorkerRuntimeFailureEvent(
        String workerId,
        Kind kind,
        String reason,
        String replyRef,
        Integer consecutiveFailures,
        String errorType,
        String errorMessage,
        Map<String, String> context
) {
    public WorkerRuntimeFailureEvent {
        workerId = requireText(workerId, "workerId");
        kind = Objects.requireNonNull(kind, "kind is required");
        reason = requireText(reason, "reason");
        context = Map.copyOf(Objects.requireNonNullElse(context, Map.of()));
    }

    static WorkerRuntimeFailureEvent startup(String workerId,
                                             WorkerRuntimeStartupStep failedStep,
                                             WorkerRuntimeStartupStep lastSuccessfulStep,
                                             Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                Kind.STARTUP,
                failedStep == null ? "STARTUP_FAILED" : failedStep.name(),
                null,
                null,
                errorType(cause),
                errorMessage(cause),
                context(
                        "failedStep", failedStep == null ? null : failedStep.name(),
                        "lastSuccessfulStep", lastSuccessfulStep == null ? null : lastSuccessfulStep.name()));
    }

    static WorkerRuntimeFailureEvent handler(String workerId,
                                             String replyRef,
                                             WorkerAction action,
                                             Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                Kind.HANDLER,
                "HANDLER_ERROR",
                replyRef,
                null,
                errorType(cause),
                errorMessage(cause),
                context("eventCode", action == null ? null : action.eventCode()));
    }

    static WorkerRuntimeFailureEvent submit(String workerId,
                                            String replyRef,
                                            WorkerAction action,
                                            Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                Kind.SUBMIT,
                "SUBMIT_FAILED",
                replyRef,
                null,
                errorType(cause),
                errorMessage(cause),
                context("eventCode", action == null ? null : action.eventCode()));
    }

    static WorkerRuntimeFailureEvent queuedResultDropped(String workerId,
                                                         String replyRef,
                                                         String reason,
                                                         Throwable cause) {
        return queuedResult(workerId, Kind.QUEUED_RESULT_DROPPED, replyRef, reason, cause);
    }

    static WorkerRuntimeFailureEvent queuedResultAbandoned(String workerId,
                                                           String replyRef,
                                                           String reason,
                                                           Throwable cause) {
        return queuedResult(workerId, Kind.QUEUED_RESULT_ABANDONED, replyRef, reason, cause);
    }

    static WorkerRuntimeFailureEvent poll(String workerId, int consecutiveFailures, Throwable cause) {
        return retryable(workerId, Kind.POLL, "POLL_FAILED", consecutiveFailures, cause);
    }

    static WorkerRuntimeFailureEvent heartbeat(String workerId, int consecutiveFailures, Throwable cause) {
        return retryable(workerId, Kind.HEARTBEAT, "HEARTBEAT_FAILED", consecutiveFailures, cause);
    }

    static WorkerRuntimeFailureEvent connection(String workerId, int consecutiveFailures, Throwable cause) {
        return retryable(workerId, Kind.CONNECTION, "CONNECTION_FAILED", consecutiveFailures, cause);
    }

    static WorkerRuntimeFailureEvent frame(String workerId, String framePreview, int frameLength,
                                           Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                Kind.FRAME,
                "FRAME_DECODE_FAILED",
                null,
                null,
                errorType(cause),
                errorMessage(cause),
                context(
                        "framePreview", framePreview,
                        "frameLength", Integer.toString(frameLength)));
    }

    static WorkerRuntimeFailureEvent shutdown(String workerId, Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                Kind.SHUTDOWN,
                "SHUTDOWN_FAILED",
                null,
                null,
                errorType(cause),
                errorMessage(cause),
                Map.of());
    }

    public enum Kind {
        STARTUP,
        HANDLER,
        SUBMIT,
        QUEUED_RESULT_DROPPED,
        QUEUED_RESULT_ABANDONED,
        POLL,
        HEARTBEAT,
        CONNECTION,
        FRAME,
        SHUTDOWN
    }

    private static WorkerRuntimeFailureEvent queuedResult(String workerId, Kind kind, String replyRef,
                                                          String reason, Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                kind,
                requireText(reason, "reason"),
                replyRef,
                null,
                errorType(cause),
                errorMessage(cause),
                Map.of());
    }

    private static WorkerRuntimeFailureEvent retryable(String workerId, Kind kind, String reason,
                                                       int consecutiveFailures, Throwable cause) {
        return new WorkerRuntimeFailureEvent(
                workerId,
                kind,
                reason,
                null,
                consecutiveFailures,
                errorType(cause),
                errorMessage(cause),
                Map.of());
    }

    private static Map<String, String> context(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("context requires key/value pairs");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i];
            String value = keyValues[i + 1];
            if (key != null && !key.isBlank() && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String errorType(Throwable cause) {
        return cause == null ? null : cause.getClass().getName();
    }

    private static String errorMessage(Throwable cause) {
        return cause == null ? null : cause.getMessage();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
