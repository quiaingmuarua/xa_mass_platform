package com.xa.mass.trace.sink;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Structured execution event written to the trace sink.
 *
 * <p>Schema version: {@code xa.mass.execution-event.v1}
 * <p>Build events using {@link #builder()}.
 */
public final class ExecutionEvent {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    // ── top-level fields ────────────────────────────────────────────────────

    private final String schema = "xa.mass.execution-event.v1";
    private final String eventId;
    private final ExecutionEventType eventType;
    private final EventCategory category;
    private final EventSeverity severity;
    private final long ts;
    private final String tsIso;

    // ── distributed tracing (nullable, OTel-ready) ──────────────────────────

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    // ── context groups ───────────────────────────────────────────────────────

    private final NodeContext node;
    private final IdentityContext identity;
    private final TransitionContext transition;
    private final OutcomeContext outcome;
    private final Map<String, Object> attrs;

    // ── nested value types ───────────────────────────────────────────────────

    public record NodeContext(String serverNodeId, String engineNodeId, String adapterNodeId) {}

    public record IdentityContext(
            String taskId,
            String messageId,
            String attemptId,
            String workerId,
            String endpointId,
            String routeKey,
            String leaseToken
    ) {}

    public record TransitionContext(String src, String dst, String reason) {}

    public record OutcomeContext(boolean success, String errorCode, String detail) {}

    // ── constructor (builder use only) ───────────────────────────────────────

    private ExecutionEvent(Builder b) {
        this.eventId = b.eventId;
        this.eventType = b.eventType;
        this.category = b.category;
        this.severity = b.severity;
        this.ts = b.ts;
        this.tsIso = b.tsIso;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.parentSpanId = b.parentSpanId;
        this.node = b.node;
        this.identity = b.identity;
        this.transition = b.transition;
        this.outcome = b.outcome;
        this.attrs = Collections.unmodifiableMap(b.attrs);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public String getSchema() { return schema; }
    public String getEventId() { return eventId; }
    public ExecutionEventType getEventType() { return eventType; }
    public EventCategory getCategory() { return category; }
    public EventSeverity getSeverity() { return severity; }
    public long getTs() { return ts; }
    public String getTsIso() { return tsIso; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public NodeContext getNode() { return node; }
    public IdentityContext getIdentity() { return identity; }
    public TransitionContext getTransition() { return transition; }
    public OutcomeContext getOutcome() { return outcome; }
    public Map<String, Object> getAttrs() { return attrs; }

    // ── builder factory ───────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    // ── builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private String eventId;
        private ExecutionEventType eventType;
        private EventCategory category;
        private EventSeverity severity;
        private long ts;
        private String tsIso;
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private NodeContext node;
        private IdentityContext identity;
        private TransitionContext transition;
        private OutcomeContext outcome;
        private Map<String, Object> attrs = new HashMap<>();

        private Builder() {}

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder eventType(ExecutionEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder category(EventCategory category) {
            this.category = category;
            return this;
        }

        public Builder severity(EventSeverity severity) {
            this.severity = severity;
            return this;
        }

        /** Set both ts (epoch millis) and tsIso together from a given instant. */
        public Builder timestamp(Instant instant) {
            this.ts = instant.toEpochMilli();
            this.tsIso = ISO_UTC.format(instant);
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder parentSpanId(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        public Builder node(String serverNodeId, String engineNodeId, String adapterNodeId) {
            this.node = new NodeContext(serverNodeId, engineNodeId, adapterNodeId);
            return this;
        }

        public Builder node(NodeContext node) {
            this.node = node;
            return this;
        }

        /** Fluent identity builder via consumer. */
        public Builder identity(Consumer<IdentityBuilder> config) {
            IdentityBuilder ib = new IdentityBuilder();
            config.accept(ib);
            this.identity = ib.build();
            return this;
        }

        public Builder identity(IdentityContext identity) {
            this.identity = identity;
            return this;
        }

        public Builder transition(String src, String dst, String reason) {
            this.transition = new TransitionContext(src, dst, reason);
            return this;
        }

        public Builder transition(TransitionContext transition) {
            this.transition = transition;
            return this;
        }

        public Builder outcome(boolean success, String errorCode, String detail) {
            this.outcome = new OutcomeContext(success, errorCode, detail);
            return this;
        }

        public Builder outcome(OutcomeContext outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder attrs(Map<String, Object> attrs) {
            this.attrs = attrs != null ? new HashMap<>(attrs) : new HashMap<>();
            return this;
        }

        public ExecutionEvent build() {
            if (eventType == null) {
                throw new IllegalStateException("eventType is required");
            }
            if (eventId == null) {
                eventId = UUID.randomUUID().toString();
            }
            if (ts == 0) {
                timestamp(Instant.now());
            }
            if (category == null) {
                category = eventType.defaultCategory();
            }
            if (severity == null) {
                severity = eventType.defaultSeverity();
            }
            if (identity == null) {
                identity = new IdentityBuilder().build();
            }
            return new ExecutionEvent(this);
        }
    }

    // ── identity sub-builder ──────────────────────────────────────────────────

    public static final class IdentityBuilder {

        private String taskId;
        private String messageId;
        private String attemptId;
        private String workerId;
        private String endpointId;
        private String routeKey;
        private String leaseToken;

        public IdentityBuilder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public IdentityBuilder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public IdentityBuilder attemptId(String attemptId) {
            this.attemptId = attemptId;
            return this;
        }

        public IdentityBuilder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public IdentityBuilder endpointId(String endpointId) {
            this.endpointId = endpointId;
            return this;
        }

        public IdentityBuilder routeKey(String routeKey) {
            this.routeKey = routeKey;
            return this;
        }

        public IdentityBuilder leaseToken(String leaseToken) {
            this.leaseToken = leaseToken;
            return this;
        }

        public IdentityContext build() {
            return new IdentityContext(
                    taskId, messageId, attemptId,
                    workerId, endpointId, routeKey, leaseToken);
        }
    }
}
