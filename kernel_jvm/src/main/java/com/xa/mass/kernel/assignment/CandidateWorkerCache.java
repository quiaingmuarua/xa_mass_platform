package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface CandidateWorkerCache {

    List<String> appendCandidateWorkers(
            String candidateId,
            int maximumCandidateWorkers,
            List<CandidateWorkerEntry> candidateWorkers,
            long expiresAtMillis
    );

    Map<String, Integer> candidateWorkerCounts(List<String> candidateIds);

    List<CandidateWorkerEntry> consumeCandidateWorkers(
            String candidateId,
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
