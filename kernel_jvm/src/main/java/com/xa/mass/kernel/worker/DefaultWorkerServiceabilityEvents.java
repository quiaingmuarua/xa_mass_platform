package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed Worker serviceability mechanism used by production Kernel Pacers. */
public final class DefaultWorkerServiceabilityEvents
        implements WorkerServiceabilityEvents {

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerScoreCore workerScores;

    public DefaultWorkerServiceabilityEvents(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScores
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
    }

    @Override
    public void onAvailable(Map<String, NetworkObservation> observedAtByWorkerId) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.HOT_ACQUIRE
        );
    }

    @Override
    public void onRouteUnavailable(
            Map<String, NetworkObservation> observedAtByWorkerId
    ) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    @Override
    public void onProbeUnavailable(
            Map<String, NetworkObservation> observedAtByWorkerId
    ) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    private void apply(
            LinkedHashMap<String, NetworkObservation> evidence,
            WorkerScorePolarity targetPolarity
    ) {
        if (evidence.isEmpty()) {
            return;
        }
        LinkedHashMap<String, LinkedHashMap<String, Long>> evidenceByGroup =
                new LinkedHashMap<>();
        List<String> workerIds = new ArrayList<>(evidence.keySet());
        int bindingLimit = WorkerResourceCatalog.MAX_WORKER_BATCH_SIZE;
        for (int offset = 0; offset < workerIds.size(); offset += bindingLimit) {
            List<String> ids = workerIds.subList(offset, Math.min(offset + bindingLimit, workerIds.size()));
            Map<String, WorkerDescriptor> bindings = workerCatalog.getWorkerDescriptors(ids);
            for (String workerId : ids) {
                NetworkObservation observation = evidence.get(workerId);
                WorkerDescriptor binding = bindings.get(workerId);
                if (binding != null && binding.endpointManagerId().equals(observation.endpointManagerId())) {
                    evidenceByGroup.computeIfAbsent(binding.workerGroupId(), ignored -> new LinkedHashMap<>())
                            .put(workerId, observation.observedAtMillis());
                }
            }
        }

        int limit = WorkerScoreCore.MAX_SERVICEABILITY_BATCH_SIZE;
        evidenceByGroup.forEach((workerGroupId, groupEvidence) -> {
            List<Map.Entry<String, Long>> entries = new ArrayList<>(
                    groupEvidence.entrySet()
            );
            for (int offset = 0; offset < entries.size(); offset += limit) {
                LinkedHashMap<String, Long> chunk = new LinkedHashMap<>();
                entries.subList(
                        offset,
                        Math.min(offset + limit, entries.size())
                ).forEach(entry -> chunk.put(
                        entry.getKey(),
                        entry.getValue()
                ));
                workerScores.applyServiceabilityEvidence(
                        workerGroupId,
                        chunk,
                        targetPolarity
                );
            }
        });
    }

    private static LinkedHashMap<String, NetworkObservation> validatedEvidence(
            Map<String, NetworkObservation> source
    ) {
        Objects.requireNonNull(source, "observedAtByWorkerId");
        LinkedHashMap<String, NetworkObservation> copied = new LinkedHashMap<>();
        source.forEach((workerId, observation) -> {
            requireNonBlank(workerId, "workerId");
            copied.put(workerId, Objects.requireNonNull(observation, "observation"));
        });
        return copied;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
