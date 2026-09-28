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

    private final WorkerScoreCore workerScores;
    private final long activationFloorMillis;

    public DefaultWorkerServiceabilityEvents(
            WorkerScoreCore workerScores,
            long activationFloorMillis
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        if (activationFloorMillis <= 0) {
            throw new IllegalArgumentException("activationFloorMillis must be positive");
        }
        this.activationFloorMillis = activationFloorMillis;
    }

    @Override
    public void onAvailable(String workerGroupId, Map<String, Long> observedAtByWorkerId) {
        apply(
                workerGroupId,
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.HOT_ACQUIRE
        );
    }

    @Override
    public void onRouteUnavailable(
            String workerGroupId, Map<String, Long> observedAtByWorkerId
    ) {
        apply(
                workerGroupId,
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    @Override
    public void onProbeUnavailable(
            String workerGroupId, Map<String, Long> observedAtByWorkerId
    ) {
        apply(
                workerGroupId,
                validatedEvidence(observedAtByWorkerId),
                WorkerScorePolarity.RECOVERY_RECHECK
        );
    }

    private void apply(
            String workerGroupId,
            LinkedHashMap<String, Long> evidence,
            WorkerScorePolarity targetPolarity
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (evidence.isEmpty()) {
            return;
        }
        int limit = WorkerScoreCore.MAX_SCORE_BATCH_SIZE;
        List<Map.Entry<String, Long>> entries = new ArrayList<>(evidence.entrySet());
        for (int offset = 0; offset < entries.size(); offset += limit) {
            LinkedHashMap<String, Long> chunk = new LinkedHashMap<>();
            entries.subList(
                    offset,
                    Math.min(offset + limit, entries.size())
            ).forEach(entry -> chunk.put(entry.getKey(), entry.getValue()));
            workerScores.rewriteCurrentPolarityWithinTimeFence(
                    workerGroupId,
                    chunk,
                    targetPolarity,
                    targetPolarity == WorkerScorePolarity.HOT_ACQUIRE ? activationFloorMillis : 0
            );
        }
    }

    private static LinkedHashMap<String, Long> validatedEvidence(
            Map<String, Long> source
    ) {
        Objects.requireNonNull(source, "observedAtByWorkerId");
        LinkedHashMap<String, Long> copied = new LinkedHashMap<>();
        source.forEach((workerId, time) -> {
            requireNonBlank(workerId, "workerId");
            if (time == null || time <= 0) throw new IllegalArgumentException("Evidence time must be positive");
            copied.put(workerId, time);
        });
        return copied;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
