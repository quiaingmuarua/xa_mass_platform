package com.xa.mass.sdk.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ordered runtime result row exposed by SDK result queries.
 *
 */
public final class TaskResultItemSnapshot {
    private final long seq;
    private final String messageId;
    private final String eventCode;
    private final String status;
    private final String finalReason;
    private final int retryCount;
    private final int maxRetryCount;
    private final String workerId;
    private final String batchId;
    private final String attemptId;
    private final String payloadRef;
    private final Instant createTime;
    private final Instant assignedTime;
    private final Instant startTime;
    private final Instant completeTime;
    private final Instant updateTime;
    private final String errorCode;
    private final String errorMessage;
    private final Map<String, Object> output;

    public TaskResultItemSnapshot(long seq, String messageId, String eventCode, String status, String finalReason,
                                  int retryCount, int maxRetryCount, String workerId,
                                  String batchId, String attemptId, String payloadRef, Instant createTime,
                                  Instant assignedTime, Instant startTime, Instant completeTime, Instant updateTime,
                                  String errorCode, String errorMessage, Map<String, Object> output) {
        this.seq = seq;
        this.messageId = messageId;
        this.eventCode = eventCode;
        this.status = status;
        this.finalReason = finalReason;
        this.retryCount = retryCount;
        this.maxRetryCount = maxRetryCount;
        this.workerId = workerId;
        this.batchId = batchId;
        this.attemptId = attemptId;
        this.payloadRef = payloadRef;
        this.createTime = createTime;
        this.assignedTime = assignedTime;
        this.startTime = startTime;
        this.completeTime = completeTime;
        this.updateTime = updateTime;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.output = output == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(output));
    }

    public long getSeq() { return seq; }
    public String getMessageId() { return messageId; }
    public String getEventCode() { return eventCode; }
    public String getStatus() { return status; }
    public String getFinalReason() { return finalReason; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetryCount() { return maxRetryCount; }
    public String getWorkerId() { return workerId; }
    public String getBatchId() { return batchId; }
    public String getAttemptId() { return attemptId; }
    public String getPayloadRef() { return payloadRef; }
    public Instant getCreateTime() { return createTime; }
    public Instant getAssignedTime() { return assignedTime; }
    public Instant getStartTime() { return startTime; }
    public Instant getCompleteTime() { return completeTime; }
    public Instant getUpdateTime() { return updateTime; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Map<String, Object> getOutput() { return output; }
}
