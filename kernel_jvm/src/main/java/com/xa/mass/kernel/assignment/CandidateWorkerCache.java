package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Task-scoped held candidates; sharing a matching Rule does not share this cache. */
public interface CandidateWorkerCache {

    List<String> appendCandidateWorkers(
            String taskId,
            int maximumCandidateWorkers,
            List<CandidateWorkerEntry> candidateWorkers,
            long expiresAtMillis
    );

    Map<String, Integer> candidateWorkerCounts(List<String> taskIds);

    List<CandidateWorkerEntry> consumeCandidateWorkers(
            String taskId,
            int limit
    );

    record CandidateWorkerEntry(
            String workerId,
            long heldWorkerLeaseScore
    ) {
        public CandidateWorkerEntry {
            Objects.requireNonNull(workerId, "workerId");
        }
    }
}
