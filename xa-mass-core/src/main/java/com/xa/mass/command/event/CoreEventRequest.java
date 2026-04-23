package com.xa.mass.command.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core runtime event request.
 */
public final class CoreEventRequest {

    private final String event;
    private final String project;
    private final Map<String, Object> payload;
    private final Map<String, String> headers;
    private final String requestId;

    private CoreEventRequest(Builder builder) {
        this.event = requireNonBlank(builder.event, "event");
        this.project = trimToNull(builder.project);
        this.payload = immutableMap(builder.payload);
        this.headers = immutableHeaders(builder.headers);
        this.requestId = trimToNull(builder.requestId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEvent() {
        return event;
    }

    public String getProject() {
        return project;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getRequestId() {
        return requestId;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, String> immutableHeaders(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Builder {
        private String event;
        private String project;
        private Map<String, Object> payload = Collections.emptyMap();
        private Map<String, String> headers = Collections.emptyMap();
        private String requestId;

        private Builder() {
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload != null ? payload : Collections.emptyMap();
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers != null ? headers : Collections.emptyMap();
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public CoreEventRequest build() {
            return new CoreEventRequest(this);
        }
    }
}
