package com.xa.mass.transport.channel;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;
import java.util.Objects;

/**
 * Worker-facing dispatch item returned by polling pull channels.
 */
public final class PulledTaskDispatch {

    private final String taskId;
    private final String messageId;
    private final String eventCode;
    private final Map<String, Object> input;
    private final Map<String, Object> sharedConfig;
    private final String attemptId;
    private final int attemptNo;
    private final int retryCount;
    private final String batchId;

    public PulledTaskDispatch(String taskId,
                              String messageId,
                              String eventCode,
                              Map<String, Object> input,
                              Map<String, Object> sharedConfig,
                              String attemptId,
                              int attemptNo,
                              int retryCount,
                              String batchId) {
        this.taskId = requireText(taskId, "taskId");
        this.messageId = requireText(messageId, "messageId");
        this.eventCode = optionalText(eventCode);
        this.input = TransportJsonValueNormalizer.normalizeObject(input, "input");
        this.sharedConfig = TransportJsonValueNormalizer.normalizeObject(sharedConfig, "sharedConfig");
        this.attemptId = optionalText(attemptId);
        this.attemptNo = Math.max(0, attemptNo);
        this.retryCount = Math.max(0, retryCount);
        this.batchId = optionalText(batchId);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getEventCode() {
        return eventCode;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getBatchId() {
        return batchId;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PulledTaskDispatch that)) {
            return false;
        }
        return attemptNo == that.attemptNo
                && retryCount == that.retryCount
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(eventCode, that.eventCode)
                && Objects.equals(input, that.input)
                && Objects.equals(sharedConfig, that.sharedConfig)
                && Objects.equals(attemptId, that.attemptId)
                && Objects.equals(batchId, that.batchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, messageId, eventCode, input, sharedConfig, attemptId, attemptNo, retryCount, batchId);
    }

    @Override
    public String toString() {
        return "PulledTaskDispatch{"
                + "taskId='" + taskId + '\''
                + ", messageId='" + messageId + '\''
                + ", eventCode='" + eventCode + '\''
                + ", attemptId='" + attemptId + '\''
                + ", attemptNo=" + attemptNo
                + ", retryCount=" + retryCount
                + ", batchId='" + batchId + '\''
                + '}';
    }
}
