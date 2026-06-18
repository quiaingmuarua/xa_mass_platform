package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerHandlerEvidence;
import com.xa.mass.client.worker.WorkerHandlerEvidenceResult;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.WorkerRuntimeEvidence;
import com.xa.mass.client.worker.WorkerRuntimeEvidenceResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkerRuntimeReporter {
    private final WorkerClient workerClient;
    private final String workerId;
    private final List<String> eventCodes;
    private final Map<String, String> attributes;

    WorkerRuntimeReporter(WorkerClient workerClient, WorkerRuntimeDefinition definition) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        WorkerRuntimeDefinition resolved = Objects.requireNonNull(definition, "definition is required");
        this.workerId = requireText(resolved.workerId(), "workerId");
        this.eventCodes = List.copyOf(resolved.eventCodes());
        this.attributes = Map.copyOf(resolved.attributes());
    }

    public String workerId() {
        return workerId;
    }

    public WorkerHandlerEvidenceResult reportHandlerEvidence() {
        return reportHandlerEvidence(null, null);
    }

    public WorkerHandlerEvidenceResult reportHandlerEvidence(Long evidenceVersion, String agentVersion) {
        return workerClient.reportHandlerEvidence(workerId, WorkerHandlerEvidence.builder()
                .workerId(workerId)
                .evidenceVersion(evidenceVersion)
                .eventCodes(new ArrayList<>(eventCodes))
                .attributes(new LinkedHashMap<>(attributes))
                .agentVersion(agentVersion)
                .build());
    }

    public WorkerRuntimeEvidenceResult reportRuntimeEvidence(WorkerRuntimeEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence is required");
        WorkerRuntimeEvidence resolved = WorkerRuntimeEvidence.builder()
                .workerId(workerId)
                .evidenceVersion(evidence.evidenceVersion())
                .state(evidence.state())
                .reason(evidence.reason())
                .observedAt(evidence.observedAt())
                .attributes(evidence.attributes())
                .build();
        return workerClient.reportRuntimeEvidence(workerId, resolved);
    }

    public WorkerRuntimeEvidenceResult reportAvailable(String reason) {
        return reportRuntimeEvidence(WorkerRuntimeEvidence.builder()
                .available()
                .reason(reason)
                .observedAt(Instant.now())
                .build());
    }

    public WorkerRuntimeEvidenceResult reportDraining(String reason) {
        return reportRuntimeEvidence(WorkerRuntimeEvidence.builder()
                .draining()
                .reason(reason)
                .observedAt(Instant.now())
                .build());
    }

    public WorkerRuntimeEvidenceResult reportOffline(String reason) {
        return reportRuntimeEvidence(WorkerRuntimeEvidence.builder()
                .offline()
                .reason(reason)
                .observedAt(Instant.now())
                .build());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
