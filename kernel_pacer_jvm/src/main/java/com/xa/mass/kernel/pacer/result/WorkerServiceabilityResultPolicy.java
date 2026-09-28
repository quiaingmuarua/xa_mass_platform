package com.xa.mass.kernel.pacer.result;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.ADAPTER_COMMAND_SUCCEEDED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.ADAPTER_WORKER_CONNECTION_CHANGED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.ADAPTER_WORKER_DELIVERY_EXPIRED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SERVER_WORKER_POLL_OBSERVED;

import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class WorkerServiceabilityResultPolicy {

    private static final System.Logger LOG = System.getLogger(WorkerServiceabilityResultPolicy.class.getName());

    private static final String CONNECTION_EVIDENCE_FORWARD =
            "worker-serviceability-evidence:v1";
    private static final String PROBE_FORWARD_PREFIX =
            "worker-serviceability:v1:";
    private static final String CONNECTED = "CONNECTED";

    private final WorkerServiceabilityEvents workerEvents;
    private final WorkerResourceCatalog workerCatalog;
    private final BiFunction<String, Map<String, Long>, Set<String>> networkEvidenceFilter;
    private final WorkerServiceabilityResultConfig config;
    private final LongSupplier currentTimeMillis;
    private final JsonMapper json;

    WorkerServiceabilityResultPolicy(
            WorkerServiceabilityEvents workerEvents,
            WorkerResourceCatalog workerCatalog,
            BiFunction<String, Map<String, Long>, Set<String>> networkEvidenceFilter,
            WorkerServiceabilityResultConfig config
    ) {
        this(
                workerEvents,
                workerCatalog,
                networkEvidenceFilter,
                config,
                System::currentTimeMillis,
                JsonMapper.builder().build()
        );
    }

    WorkerServiceabilityResultPolicy(
            WorkerServiceabilityEvents workerEvents,
            WorkerResourceCatalog workerCatalog,
            BiFunction<String, Map<String, Long>, Set<String>> networkEvidenceFilter,
            WorkerServiceabilityResultConfig config,
            LongSupplier currentTimeMillis,
            JsonMapper json
    ) {
        this.workerCatalog = Objects.requireNonNull(workerCatalog, "workerCatalog");
        this.networkEvidenceFilter = Objects.requireNonNull(networkEvidenceFilter, "networkEvidenceFilter");
        this.workerEvents = java.util.Objects.requireNonNull(
                workerEvents,
                "workerEvents"
        );
        this.config = java.util.Objects.requireNonNull(config, "config");
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

        var byGroup = new LinkedHashMap<String, LinkedHashMap<String, WorkerEvidence>>();
        var ids = new ArrayList<>(latestEvidence.keySet());
        int bindingLimit = WorkerResourceCatalog.MAX_WORKER_BATCH_SIZE;
        for (int offset = 0; offset < ids.size(); offset += bindingLimit) {
            var page = ids.subList(offset, Math.min(offset + bindingLimit, ids.size()));
            var bindings = workerCatalog.getWorkerDescriptors(page);
            for (String id : page) {
                var evidence = latestEvidence.get(id);
                var binding = bindings.get(id);
                if (binding != null && binding.endpointManagerId().equals(evidence.endpointManagerId())) {
                    byGroup.computeIfAbsent(binding.workerGroupId(), ignored -> new LinkedHashMap<>())
                            .put(id, evidence);
                }
            }
        }
        byGroup.forEach(this::applyGroup);
    }

    private void applyGroup(String group, LinkedHashMap<String, WorkerEvidence> evidenceById) {
        var times = new LinkedHashMap<String, Long>();
        evidenceById.forEach((id, evidence) -> times.put(id, evidence.observedAtMillis()));
        Set<String> admitted;
        try {
            admitted = Objects.requireNonNull(networkEvidenceFilter.apply(group, Collections.unmodifiableMap(times)),
                    "Network evidence filter returned null");
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING,
                    "operation=serviceability.filterEvidence count=" + times.size() + " continuing without filter", failure);
            admitted = times.keySet();
        }

        var available = new LinkedHashMap<String, Long>();
        var routeUnavailable = new LinkedHashMap<String, Long>();
        var probeUnavailable = new LinkedHashMap<String, Long>();
        for (var entry : evidenceById.entrySet()) {
            if (!admitted.contains(entry.getKey())) continue;
            WorkerEvidence evidence = entry.getValue();
            Map<String, Long> target = switch (evidence.kind()) {
                case AVAILABLE -> available;
                case ROUTE_UNAVAILABLE -> routeUnavailable;
                case PROBE_UNAVAILABLE -> probeUnavailable;
            };
            target.put(entry.getKey(), evidence.observedAtMillis());
        }
        if (!available.isEmpty()) {
            workerEvents.onAvailable(group, available);
        }
        if (!routeUnavailable.isEmpty()) {
            workerEvents.onRouteUnavailable(group, routeUnavailable);
        }
        if (!probeUnavailable.isEmpty()) {
            workerEvents.onProbeUnavailable(group, probeUnavailable);
        }
    }

    private Map<String, WorkerEvidence> decodeReport(
            DeliveryReport report,
            long nowMillis,
            long evidenceMaxAgeMillis
    ) {
        if (report == null
                || (report.src() != DeliveryEndpoint.ADAPTER && report.src() != DeliveryEndpoint.SERVER)
                || report.dst() != DeliveryEndpoint.KERNEL
                || report.sourceId() == null
                || report.sourceId().isEmpty()) {
            return null;
        }
        Map<String, WorkerEvidence> decoded;
        if (report.src() == DeliveryEndpoint.SERVER) {
            if (!"system-polling".equals(report.sourceId())
                    || !SERVER_WORKER_POLL_OBSERVED.equals(report.messageType())) {
                return null;
            }
            decoded = decodeObservation(report, EvidenceKind.AVAILABLE);
        } else if (ADAPTER_WORKER_CONNECTION_CHANGED.equals(report.messageType())) {
            decoded = decodeConnectionChange(report);
        } else if (ADAPTER_WORKER_DELIVERY_EXPIRED.equals(report.messageType())) {
            decoded = decodeObservation(report, EvidenceKind.ROUTE_UNAVAILABLE);
        } else if (ADAPTER_COMMAND_SUCCEEDED.equals(report.messageType())) {
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

    private Map<String, WorkerEvidence> decodeObservation(DeliveryReport report, EvidenceKind kind) {
        if (!CONNECTION_EVIDENCE_FORWARD.equals(report.forward())) {
            return null;
        }
        JsonNode payload = payload(report.payload());
        if (!hasFields(payload, "workerId", "observedAtMillis")) {
            return null;
        }
        String workerId = nonEmptyText(payload.get("workerId"));
        Long observedAt = positiveLong(payload.get("observedAtMillis"));
        return workerId == null || observedAt == null ? null : Map.of(workerId,
                new WorkerEvidence(report.sourceId(), observedAt, kind));
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
                        report.sourceId(), observedAt,
                        CONNECTED.equals(state)
                                ? EvidenceKind.AVAILABLE
                                : EvidenceKind.ROUTE_UNAVAILABLE
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
                kind = EvidenceKind.AVAILABLE;
            } else if ("DISCONNECTED".equals(state)
                    || "UNKNOWN".equals(state)) {
                kind = EvidenceKind.PROBE_UNAVAILABLE;
            } else {
                return null;
            }
            evidence.put(workerId, new WorkerEvidence(report.sourceId(), checkStarted, kind));
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
        AVAILABLE,
        ROUTE_UNAVAILABLE,
        PROBE_UNAVAILABLE
    }

    private record WorkerEvidence(
            String endpointManagerId,
            long observedAtMillis,
            EvidenceKind kind
    ) {
    }
}
