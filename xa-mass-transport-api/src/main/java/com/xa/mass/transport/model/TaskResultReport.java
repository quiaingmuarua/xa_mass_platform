package com.xa.mass.transport.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-neutral task execution result reported by a worker.
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
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
