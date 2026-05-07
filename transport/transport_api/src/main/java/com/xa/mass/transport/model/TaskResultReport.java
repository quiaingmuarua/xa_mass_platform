package com.xa.mass.transport.model;

import com.xa.mass.transport.payload.TransportJsonValueNormalizer;

import java.util.Map;

/**
 * Transport-neutral task execution result reported by a worker.
 *
 * <p>{@code output} is a JSON-object payload boundary. Values must remain
 * JSON-safe so result reports can round-trip through non-memory transport
 * queues and codecs without relying on JVM-local object shapes.</p>
 */
public final class TaskResultReport {

    private final String taskId;
    private final String messageId;
    private final boolean success;
    private final String detail;
    private final String errorCode;
    private final Map<String, Object> output;

    public TaskResultReport(String taskId,
                            String messageId,
                            boolean success,
                            String detail,
                            String errorCode,
                            Map<String, Object> output) {
        this.taskId = taskId;
        this.messageId = messageId;
        this.success = success;
        this.detail = detail;
        this.errorCode = errorCode;
        this.output = immutableCopy(output);
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return TransportJsonValueNormalizer.normalizeObject(values, "output");
    }
}
