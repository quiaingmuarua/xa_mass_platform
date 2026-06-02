package com.xa.mass.sdk.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stable SDK event invocation request.
 */
public final class EventRequest {

    private final EventCode event;
    private final String project;
    private final Map<String, Object> payload;
    private final Map<String, String> headers;
    private final String requestId;

    private EventRequest(Builder builder) {
        this.event = Objects.requireNonNull(builder.event, "event");
        this.project = trimToNull(builder.project);
        this.payload = immutablePayload(builder.payload);
        this.headers = immutableHeaders(builder.headers);
        this.requestId = trimToNull(builder.requestId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public EventCode getEvent() {
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

    private static Map<String, Object> immutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static Map<String, String> immutableHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Builder {
        private EventCode event;
        private String project;
        private Map<String, Object> payload = Collections.emptyMap();
        private Map<String, String> headers = Collections.emptyMap();
        private String requestId;

        private Builder() {
        }

        public Builder event(String eventCode) {
            this.event = EventCode.of(eventCode);
            return this;
        }

        public Builder event(EventCode event) {
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

        public EventRequest build() {
            return new EventRequest(this);
        }
    }
}
