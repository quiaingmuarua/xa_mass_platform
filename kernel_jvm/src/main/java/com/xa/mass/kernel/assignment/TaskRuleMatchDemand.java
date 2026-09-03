package com.xa.mass.kernel.assignment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One bounded PRECOMPUTED matching request. */
public record TaskRuleMatchDemand(
        String workerGroupId,
        List<TaskCandidateNeed> orderedTaskNeeds,
        Map<String, Long> heldWorkerLeaseScores,
        long holdUntilMillis
) {

    public static final int MAX_TASKS = 100;
    public static final int MAX_HELD_WORKERS = 100;

    public TaskRuleMatchDemand {
        requireNonBlank(workerGroupId, "workerGroupId");
        orderedTaskNeeds = immutableTaskNeeds(orderedTaskNeeds);
        heldWorkerLeaseScores = immutableHeldScores(
                heldWorkerLeaseScores
        );
        if (holdUntilMillis < 1) {
            throw new IllegalArgumentException(
                    "holdUntilMillis must be positive"
            );
        }
    }

    public record TaskCandidateNeed(
            String candidateId,
            int maximumCandidateWorkers
    ) {
        public TaskCandidateNeed {
            requireNonBlank(candidateId, "candidateId");
            if (maximumCandidateWorkers <= 0) {
                throw new IllegalArgumentException(
                        "maximumCandidateWorkers must be positive"
                );
            }
        }
    }

    private static List<TaskCandidateNeed> immutableTaskNeeds(
            List<TaskCandidateNeed> values
    ) {
        Objects.requireNonNull(values, "orderedTaskNeeds");
        if (values.isEmpty() || values.size() > MAX_TASKS) {
            throw new IllegalArgumentException(
                    "orderedTaskNeeds must contain 1.." + MAX_TASKS
                            + " tasks"
            );
        }
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        for (TaskCandidateNeed value : values) {
            Objects.requireNonNull(value, "candidate need");
            if (!candidateIds.add(value.candidateId())) {
                throw new IllegalArgumentException(
                        "orderedTaskNeeds must not contain duplicate "
                                + "candidateIds"
                );
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, Long> immutableHeldScores(
            Map<String, Long> values
    ) {
        Objects.requireNonNull(values, "heldWorkerLeaseScores");
        if (values.isEmpty() || values.size() > MAX_HELD_WORKERS) {
            throw new IllegalArgumentException(
                    "heldWorkerLeaseScores must contain 1.."
                            + MAX_HELD_WORKERS + " workers"
            );
        }
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        values.forEach((workerId, score) -> {
            requireNonBlank(workerId, "workerId");
            result.put(
                    workerId,
                    Objects.requireNonNull(score, "heldWorkerLeaseScore")
            );
        });
        return Collections.unmodifiableMap(result);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
