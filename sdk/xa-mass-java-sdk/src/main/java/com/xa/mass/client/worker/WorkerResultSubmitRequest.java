package com.xa.mass.client.worker;

import java.util.Map;

public record WorkerResultSubmitRequest(
        String resultCorrelationRef,
        boolean success,
        String detail,
        String errorCode,
        Map<String, Object> output
) {
    public WorkerResultSubmitRequest {
        resultCorrelationRef = requireText(resultCorrelationRef, "resultCorrelationRef");
        output = WorkerRequestSupport.copyObjectMap(output);
    }

    public static WorkerResultSubmitRequest success(String resultCorrelationRef,
                                                    String detail,
                                                    Map<String, Object> output) {
        return builder()
                .resultCorrelationRef(resultCorrelationRef)
                .success(true)
                .detail(detail)
                .output(output)
                .build();
    }

    public static WorkerResultSubmitRequest failure(String resultCorrelationRef,
                                                    String errorCode,
                                                    String detail,
                                                    Map<String, Object> output) {
        return builder()
                .resultCorrelationRef(resultCorrelationRef)
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
        private String resultCorrelationRef;
        private boolean success;
        private String detail;
        private String errorCode;
        private Map<String, Object> output = WorkerRequestSupport.mutableMap();

        private Builder() {
        }

        public Builder resultCorrelationRef(String resultCorrelationRef) {
            this.resultCorrelationRef = resultCorrelationRef;
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
            return new WorkerResultSubmitRequest(resultCorrelationRef, success, detail, errorCode, output);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
