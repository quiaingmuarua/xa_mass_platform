package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
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

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerScoreCore workerScore;
    private final WorkerServiceabilityResultConfig config;
    private final long hotEligibilityFloorMillis;
    private final LongSupplier currentTimeMillis;
    private final JsonMapper json;

    WorkerServiceabilityResultPolicy(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScore,
            WorkerServiceabilityResultConfig config,
            long hotEligibilityFloorMillis
    ) {
        this(
                workerCatalog,
                workerScore,
                config,
                hotEligibilityFloorMillis,
                System::currentTimeMillis,
                JsonMapper.builder().build()
        );
    }

    WorkerServiceabilityResultPolicy(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScore,
            WorkerServiceabilityResultConfig config,
            long hotEligibilityFloorMillis,
            LongSupplier currentTimeMillis,
            JsonMapper json
    ) {
        this.workerCatalog = java.util.Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerScore = java.util.Objects.requireNonNull(
                workerScore,
                "workerScore"
        );
        this.config = java.util.Objects.requireNonNull(config, "config");
        WorkerServiceabilityAssemblyConfig.requireFloor(
                hotEligibilityFloorMillis
        );
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

        LinkedHashMap<String, String> groupIds = new LinkedHashMap<>();
        List<String> workerIds = new ArrayList<>(latestEvidence.keySet());
        int lookupLimit = WorkerResourceCatalog.MAX_WORKER_GROUP_LOOKUP_LIMIT;
        for (int offset = 0; offset < workerIds.size(); offset += lookupLimit) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + lookupLimit, workerIds.size())
            );
            workerCatalog.getWorkerGroupIds(chunk).forEach((workerId, groupId) -> {
                if (groupId != null) {
                    groupIds.put(workerId, groupId);
                }
            });
        }

        LinkedHashMap<String, LinkedHashMap<String, WorkerEvidence>>
                evidenceByGroup = new LinkedHashMap<>();
        latestEvidence.forEach((workerId, evidence) -> {
            String groupId = groupIds.get(workerId);
            if (groupId != null) {
                evidenceByGroup.computeIfAbsent(
                        groupId,
                        ignored -> new LinkedHashMap<>()
                ).put(workerId, evidence);
            }
        });

        for (Map.Entry<String, LinkedHashMap<String, WorkerEvidence>> group
                : evidenceByGroup.entrySet()) {
            Map<String, WorkerScoreState> states = workerScore.getScoreStates(
                    group.getKey(),
                    new ArrayList<>(group.getValue().keySet())
            );
            for (Map.Entry<String, WorkerEvidence> worker
                    : group.getValue().entrySet()) {
                WorkerScoreState state = states.get(worker.getKey());
                if (state == null) {
                    continue;
                }
                applyEvidence(
                        group.getKey(),
                        worker.getKey(),
                        state,
                        worker.getValue(),
                        config.maxRecoveryAttempts(),
                        hotEligibilityFloorMillis
                );
            }
        }
    }

    private void applyEvidence(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            WorkerEvidence evidence,
            int maxRecoveryAttempts,
            long hotEligibilityFloorMillis
    ) {
        if (state.timeMillis() == WorkerScoreCore.PAUSE_TIME_MILLIS) {
            return;
        }
        if (evidence.kind() == EvidenceKind.CONNECTED) {
            applyConnected(
                    workerGroupId,
                    workerId,
                    state,
                    hotEligibilityFloorMillis
            );
            return;
        }
        if (evidence.kind() == EvidenceKind.ROUTE_UNAVAILABLE) {
            if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
                workerScore.toggleCurrentPolarity(
                        workerGroupId,
                        workerId,
                        state.score()
                );
            }
            return;
        }
        applyProbeUnavailable(
                workerGroupId,
                workerId,
                state,
                evidence.observedAtMillis(),
                maxRecoveryAttempts
        );
    }

    private void applyConnected(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            long hotEligibilityFloorMillis
    ) {
        if (state.polarity() == WorkerScorePolarity.RECOVERY_RECHECK) {
            var toggled = workerScore.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    state.score()
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED) {
                return;
            }
        }
        workerScore.rewriteCurrentScores(
                workerGroupId,
                List.of(workerId),
                hotEligibilityFloorMillis,
                WorkerScoreCore.MIN_LANE_RANK
        );
    }

    private void applyProbeUnavailable(
            String workerGroupId,
            String workerId,
            WorkerScoreState state,
            long observedAtMillis,
            int maxRecoveryAttempts
    ) {
        if (state.polarity() == WorkerScorePolarity.HOT_ACQUIRE) {
            var toggled = workerScore.toggleCurrentPolarity(
                    workerGroupId,
                    workerId,
                    state.score()
            );
            if (toggled.status()
                    != WorkerScoreTransitionStatus.TRANSITIONED) {
                return;
            }
            workerScore.rewriteCurrentScores(
                    workerGroupId,
                    List.of(workerId),
                    observedAtMillis,
                    WorkerScoreCore.MIN_LANE_RANK
            );
            return;
        }
        int nextAttempt = state.laneRank() + 1;
        if (nextAttempt >= maxRecoveryAttempts) {
            workerScore.exhaustRecoveryRecheck(
                    workerGroupId,
                    workerId,
                    state.score(),
                    maxRecoveryAttempts
            );
            return;
        }
        workerScore.rewriteCurrentScores(
                workerGroupId,
                List.of(workerId),
                observedAtMillis,
                nextAttempt
        );
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
