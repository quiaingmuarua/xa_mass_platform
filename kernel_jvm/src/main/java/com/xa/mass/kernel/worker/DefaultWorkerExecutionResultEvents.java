package com.xa.mass.kernel.worker;

import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Fixed Worker execution result mechanism used by production Kernel Pacers. */
public final class DefaultWorkerExecutionResultEvents
        implements WorkerExecutionResultEvents {

    private final WorkerScoreCore workerScores;

    public DefaultWorkerExecutionResultEvents(WorkerScoreCore workerScores) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
    }

    @Override
    public void onTaskSucceeded(
            String workerGroupId,
            Map<String, WorkerLeaseReference> leasesByWorkerId,
            long observedAtMillis
    ) {
        Map<String, Long> scores = encodedScores(
                workerGroupId,
                leasesByWorkerId,
                observedAtMillis
        );
        if (!scores.isEmpty()) {
            workerScores.releaseCompletedHotScoreHolds(
                    workerGroupId,
                    scores,
                    observedAtMillis
            );
        }
    }

    @Override
    public void onTaskFailed(
            String workerGroupId,
            Map<String, WorkerLeaseReference> leasesByWorkerId,
            long observedAtMillis
    ) {
        Map<String, Long> scores = encodedScores(
                workerGroupId,
                leasesByWorkerId,
                observedAtMillis
        );
        if (!scores.isEmpty()) {
            workerScores.releaseScoreHolds(
                    workerGroupId,
                    scores,
                    observedAtMillis
            );
        }
    }

    private static Map<String, Long> encodedScores(
            String workerGroupId,
            Map<String, WorkerLeaseReference> leasesByWorkerId,
            long observedAtMillis
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (observedAtMillis <= 0) {
            throw new IllegalArgumentException(
                    "observedAtMillis must be positive"
            );
        }
        Objects.requireNonNull(leasesByWorkerId, "leasesByWorkerId");
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        leasesByWorkerId.forEach((workerId, reference) -> {
            requireNonBlank(workerId, "workerId");
            scores.put(
                    workerId,
                    Objects.requireNonNull(reference, "lease reference")
                            .encodedScore()
            );
        });
        return scores;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
