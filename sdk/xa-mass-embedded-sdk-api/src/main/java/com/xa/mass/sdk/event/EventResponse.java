package com.xa.mass.sdk.event;

import java.util.Objects;

/**
 * Stable SDK event response.
 */
public final class EventResponse {

    private final boolean success;
    private final String code;
    private final String message;
    private final Object data;
    private final String requestId;

    private EventResponse(Builder builder) {
        this.success = builder.success;
        this.code = normalize(builder.code);
        this.message = normalize(builder.message);
        this.data = builder.data;
        this.requestId = normalize(builder.requestId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EventResponse success(Object data, String requestId) {
        return builder()
                .success(true)
                .code("OK")
                .message("success")
                .data(data)
                .requestId(requestId)
                .build();
    }

    public static EventResponse failure(String code, String message, String requestId) {
        return builder()
                .success(false)
                .code(code)
                .message(message)
                .requestId(requestId)
                .build();
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }

    public String getRequestId() {
        return requestId;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventResponse that)) return false;
        return success == that.success
                && Objects.equals(code, that.code)
                && Objects.equals(message, that.message)
                && Objects.equals(data, that.data)
                && Objects.equals(requestId, that.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, code, message, data, requestId);
    }

    public static final class Builder {
        private boolean success;
        private String code;
        private String message;
        private Object data;
        private String requestId;

        private Builder() {
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder data(Object data) {
            this.data = data;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public EventResponse build() {
            return new EventResponse(this);
        }
    }
}
