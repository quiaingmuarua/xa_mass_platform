package com.xa.mass.kernel.pacer.result;

import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class WorkerServiceabilityResultPolicy {

    private static final String CONNECTION_CHANGED_EVENT =
            "platform.adapter.worker-connection.changed";
    private static final String DELIVERY_EXPIRED_EVENT =
            "platform.adapter.worker-delivery.expired";
    private static final String PROBE_EVENT =
            "platform.adapter.worker-connections.snapshot";
    private static final String CONNECTION_EVIDENCE_FORWARD =
            "worker-serviceability-evidence:v1";
    private static final String PROBE_FORWARD_PREFIX =
            "worker-serviceability:v1:";
    private static final String CONNECTED = "CONNECTED";

    private final WorkerServiceabilityEvents workerEvents;
    private final WorkerServiceabilityResultConfig config;
    private final long hotEligibilityFloorMillis;
    private final LongSupplier currentTimeMillis;
    private final JsonMapper json;

    WorkerServiceabilityResultPolicy(
            WorkerServiceabilityEvents workerEvents,
            WorkerServiceabilityResultConfig config,
            long hotEligibilityFloorMillis
    ) {
        this(
                workerEvents,
                config,
                hotEligibilityFloorMillis,
                System::currentTimeMillis,
                JsonMapper.builder().build()
        );
    }

    WorkerServiceabilityResultPolicy(
            WorkerServiceabilityEvents workerEvents,
            WorkerServiceabilityResultConfig config,
            long hotEligibilityFloorMillis,
            LongSupplier currentTimeMillis,
            JsonMapper json
    ) {
        this.workerEvents = java.util.Objects.requireNonNull(
                workerEvents,
                "workerEvents"
        );
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    void handle(List<DeliveryReport> reports) {
        java.util.Objects.requireNonNull(reports, "reports");
        long nowMillis = currentTimeMillis.getAsLong();
        LinkedHashMap<String, WorkerEvidence> latestEvidence =
                new LinkedHashMap<>();
        for (DeliveryReport report : reports) {
            Map<String, WorkerEvidence> decoded = decodeReport(
                    report,
                    nowMillis,
                    config.evidenceMaxAgeMillis()
            );
            if (decoded == null) {
                continue;
            }
            decoded.forEach((workerId, evidence) -> {
                WorkerEvidence previous = latestEvidence.get(workerId);
                if (previous == null
                        || evidence.observedAtMillis()
                        >= previous.observedAtMillis()) {
                    latestEvidence.put(workerId, evidence);
                }
            });
        }
        if (latestEvidence.isEmpty()) {
            return;
        }

        LinkedHashMap<String, Long> connected = new LinkedHashMap<>();
        LinkedHashMap<String, Long> routeUnavailable = new LinkedHashMap<>();
        LinkedHashMap<String, Long> probeUnavailable = new LinkedHashMap<>();
        latestEvidence.forEach((workerId, evidence) -> {
            switch (evidence.kind()) {
                case CONNECTED -> connected.put(
                        workerId,
                        evidence.observedAtMillis()
                );
                case ROUTE_UNAVAILABLE -> routeUnavailable.put(
                        workerId,
                        evidence.observedAtMillis()
                );
                case PROBE_UNAVAILABLE -> probeUnavailable.put(
                        workerId,
                        evidence.observedAtMillis()
                );
            }
        });
        if (!connected.isEmpty()) {
            workerEvents.onConnected(
                    connected,
                    hotEligibilityFloorMillis
            );
        }
        if (!routeUnavailable.isEmpty()) {
            workerEvents.onRouteUnavailable(routeUnavailable);
        }
        if (!probeUnavailable.isEmpty()) {
            workerEvents.onProbeUnavailable(
                    probeUnavailable,
                    config.maxRecoveryAttempts()
            );
        }
    }

    private Map<String, WorkerEvidence> decodeReport(
            DeliveryReport report,
            long nowMillis,
            long evidenceMaxAgeMillis
    ) {
        if (report == null
                || report.src() != DeliveryEndpoint.ADAPTER
                || report.dst() != DeliveryEndpoint.KERNEL
                || !"200".equals(report.outcomeCode())
                || report.sourceId() == null
                || report.sourceId().isEmpty()) {
            return null;
        }
        Map<String, WorkerEvidence> decoded;
        if (CONNECTION_CHANGED_EVENT.equals(report.messageType())) {
            decoded = decodeConnectionChange(report);
        } else if (DELIVERY_EXPIRED_EVENT.equals(report.messageType())) {
            decoded = decodeDeliveryExpired(report);
        } else if (PROBE_EVENT.equals(report.messageType())) {
            decoded = decodeProbeSnapshot(report);
        } else {
            return null;
        }
        if (decoded == null) {
            return null;
        }
        for (WorkerEvidence evidence : decoded.values()) {
            long age = nowMillis - evidence.observedAtMillis();
            if (age < 0 || age > evidenceMaxAgeMillis) {
                return null;
            }
        }
        return decoded;
    }

    private Map<String, WorkerEvidence> decodeConnectionChange(
            DeliveryReport report
    ) {
        if (!CONNECTION_EVIDENCE_FORWARD.equals(report.forward())) {
            return null;
        }
        JsonNode payload = payload(report.payload());
        if (!hasFields(payload, "workerId", "state", "observedAtMillis")) {
            return null;
        }
        String workerId = nonEmptyText(payload.get("workerId"));
        String state = text(payload.get("state"));
        Long observedAt = positiveLong(payload.get("observedAtMillis"));
        if (workerId == null
                || observedAt == null
                || !(CONNECTED.equals(state)
                || "DISCONNECTED".equals(state))) {
            return null;
        }
        return Map.of(
                workerId,
                new WorkerEvidence(
                        observedAt,
                        CONNECTED.equals(state)
                                ? EvidenceKind.CONNECTED
                                : EvidenceKind.ROUTE_UNAVAILABLE
                )
        );
    }

    private Map<String, WorkerEvidence> decodeDeliveryExpired(
            DeliveryReport report
    ) {
        if (!CONNECTION_EVIDENCE_FORWARD.equals(report.forward())) {
            return null;
        }
        JsonNode payload = payload(report.payload());
        if (!hasFields(payload, "workerId", "observedAtMillis")) {
            return null;
        }
        String workerId = nonEmptyText(payload.get("workerId"));
        Long observedAt = positiveLong(payload.get("observedAtMillis"));
        if (workerId == null || observedAt == null) {
            return null;
        }
        return Map.of(
                workerId,
                new WorkerEvidence(
                        observedAt,
                        EvidenceKind.ROUTE_UNAVAILABLE
                )
        );
    }

    private Map<String, WorkerEvidence> decodeProbeSnapshot(
            DeliveryReport report
    ) {
        String forward = report.forward();
        if (forward == null || !forward.startsWith(PROBE_FORWARD_PREFIX)) {
            return null;
        }
        String rawStarted = forward.substring(PROBE_FORWARD_PREFIX.length());
        if (rawStarted.isEmpty()
                || rawStarted.chars().anyMatch(value -> !Character.isDigit(value))) {
            return null;
        }
        long checkStarted;
        try {
            checkStarted = Long.parseLong(rawStarted);
        } catch (NumberFormatException error) {
            return null;
        }
        if (checkStarted <= 0) {
            return null;
        }
        JsonNode payload = payload(report.payload());
        if (!hasFields(payload, "stateByWorkerId")) {
            return null;
        }
        JsonNode states = payload.get("stateByWorkerId");
        if (states == null || !states.isObject()) {
            return null;
        }
        List<String> names = new ArrayList<>(states.propertyNames());
        if (names.isEmpty() || names.size() > 100) {
            return null;
        }
        LinkedHashMap<String, WorkerEvidence> evidence = new LinkedHashMap<>();
        for (String workerId : names) {
            if (workerId.isEmpty()) {
                return null;
            }
            String state = text(states.get(workerId));
            EvidenceKind kind;
            if (CONNECTED.equals(state)) {
                kind = EvidenceKind.CONNECTED;
            } else if ("DISCONNECTED".equals(state)
                    || "UNKNOWN".equals(state)) {
                kind = EvidenceKind.PROBE_UNAVAILABLE;
            } else {
                return null;
            }
            evidence.put(workerId, new WorkerEvidence(checkStarted, kind));
        }
        return evidence;
    }

    private JsonNode payload(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode decoded = json.readTree(value);
            return decoded != null && decoded.isObject() ? decoded : null;
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean hasFields(JsonNode value, String... expected) {
        if (value == null || !value.isObject()) {
            return false;
        }
        java.util.HashSet<String> fields = new java.util.HashSet<>(
                value.propertyNames()
        );
        return fields.equals(java.util.Set.of(expected));
    }

    private static String nonEmptyText(JsonNode value) {
        String decoded = text(value);
        return decoded != null && !decoded.isEmpty() ? decoded : null;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual()
                ? value.textValue()
                : null;
    }

    private static Long positiveLong(JsonNode value) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()) {
            return null;
        }
        long decoded = value.longValue();
        return decoded > 0 ? decoded : null;
    }

    private enum EvidenceKind {
        CONNECTED,
        ROUTE_UNAVAILABLE,
        PROBE_UNAVAILABLE
    }

    private record WorkerEvidence(
            long observedAtMillis,
            EvidenceKind kind
    ) {
    }
}
