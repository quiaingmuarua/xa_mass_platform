package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.score.WorkerScoreCore;
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
    public void onConnected(Map<String, Long> observedAtByWorkerId) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.HOT_ACQUIRE
        );
    }

    @Override
    public void onRouteUnavailable(
            Map<String, Long> observedAtByWorkerId
    ) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    @Override
    public void onProbeUnavailable(
            Map<String, Long> observedAtByWorkerId
    ) {
        apply(
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    private void apply(
            LinkedHashMap<String, Long> evidence,
            WorkerScorePolarity targetPolarity
    ) {
        if (evidence.isEmpty()) {
            return;
        }
        LinkedHashMap<String, String> groupIds = groupIds(
                new ArrayList<>(evidence.keySet())
        );
        LinkedHashMap<String, LinkedHashMap<String, Long>> evidenceByGroup =
                new LinkedHashMap<>();
        evidence.forEach((workerId, observedAtMillis) -> {
            String groupId = groupIds.get(workerId);
            if (groupId != null) {
                evidenceByGroup.computeIfAbsent(
                        groupId,
                        ignored -> new LinkedHashMap<>()
                ).put(workerId, observedAtMillis);
            }
        });

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
                workerScores.applyServiceabilityPolarityEvidence(
                        workerGroupId,
                        chunk,
                        targetPolarity
                );
            }
        });
    }

    private LinkedHashMap<String, String> groupIds(List<String> workerIds) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int limit = WorkerResourceCatalog.MAX_WORKER_GROUP_LOOKUP_LIMIT;
        for (int offset = 0; offset < workerIds.size(); offset += limit) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + limit, workerIds.size())
            );
            workerCatalog.getWorkerGroupIds(chunk).forEach((workerId, groupId) -> {
                if (groupId != null) {
                    result.put(workerId, groupId);
                }
            });
        }
        return result;
    }

    private static LinkedHashMap<String, Long> validatedEvidence(
            Map<String, Long> source
    ) {
        Objects.requireNonNull(source, "observedAtByWorkerId");
        LinkedHashMap<String, Long> copied = new LinkedHashMap<>();
        source.forEach((workerId, observedAtMillis) -> {
            requireNonBlank(workerId, "workerId");
            if (observedAtMillis == null || observedAtMillis <= 0) {
                throw new IllegalArgumentException(
                        "observedAtMillis must be positive"
                );
            }
            copied.put(workerId, observedAtMillis);
        });
        return copied;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
