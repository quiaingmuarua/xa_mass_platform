package com.xa.mass.client.worker;

import java.util.Map;

public record WorkerResultSubmitRequest(
        String taskId,
        String messageId,
        boolean success,
        String detail,
        String errorCode,
        Map<String, Object> output
) {
    public WorkerResultSubmitRequest {
        output = WorkerRequestSupport.copyObjectMap(output);
    }

    public static WorkerResultSubmitRequest success(String taskId,
                                                    String messageId,
                                                    String detail,
                                                    Map<String, Object> output) {
        return builder()
                .taskId(taskId)
                .messageId(messageId)
                .success(true)
                .detail(detail)
                .output(output)
                .build();
    }

    public static WorkerResultSubmitRequest failure(String taskId,
                                                    String messageId,
                                                    String errorCode,
                                                    String detail,
                                                    Map<String, Object> output) {
        return builder()
                .taskId(taskId)
                .messageId(messageId)
                .success(false)
                .errorCode(errorCode)
                .detail(detail)
                .output(output)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String taskId;
        private String messageId;
        private boolean success;
        private String detail;
        private String errorCode;
        private Map<String, Object> output = WorkerRequestSupport.mutableMap();

        private Builder() {
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder output(Map<String, Object> output) {
            this.output = output == null ? WorkerRequestSupport.mutableMap() : new java.util.LinkedHashMap<>(output);
            return this;
        }

        public Builder output(String key, Object value) {
            this.output.put(key, value);
            return this;
        }

        public WorkerResultSubmitRequest build() {
            return new WorkerResultSubmitRequest(taskId, messageId, success, detail, errorCode, output);
        }
    }
}
