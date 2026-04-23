package com.xa.mass.gateway.dispatcher.event;

import com.xa.mass.sdk.event.EventPrincipal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gateway control-plane envelope before dispatching into the event runtime.
 */
public final class EventEnvelope {

    private final String event;
    private final String project;
    private final String requestId;
    private final Map<String, String> headers;
    private final Map<String, Object> payload;
    private final EventPrincipal principal;

    private EventEnvelope(Builder builder) {
        this.event = builder.event;
        this.project = builder.project;
        this.requestId = builder.requestId;
        this.headers = immutableStringMap(builder.headers);
        this.payload = immutablePayload(builder.payload);
        this.principal = builder.principal;
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

    public String getRequestId() {
        return requestId;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public EventPrincipal getPrincipal() {
        return principal;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static final class Builder {
        private String event;
        private String project;
        private String requestId;
        private Map<String, String> headers = Collections.emptyMap();
        private Map<String, Object> payload = Collections.emptyMap();
        private EventPrincipal principal;

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

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers != null ? headers : Collections.emptyMap();
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload != null ? payload : Collections.emptyMap();
            return this;
        }

        public Builder principal(EventPrincipal principal) {
            this.principal = principal;
            return this;
        }

        public EventEnvelope build() {
            return new EventEnvelope(this);
        }
    }
}
