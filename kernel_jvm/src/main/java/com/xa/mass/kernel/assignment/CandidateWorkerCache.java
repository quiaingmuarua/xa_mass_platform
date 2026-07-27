package com.xa.mass.kernel.assignment;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface CandidateWorkerCache {

    void appendCandidateWorkers(
            String candidateId,
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
            String workerGroupId,
            String endpointManagerId,
            long workerLeaseScore
    ) {
        public CandidateWorkerEntry {
            Objects.requireNonNull(workerId, "workerId");
            Objects.requireNonNull(workerGroupId, "workerGroupId");
            Objects.requireNonNull(endpointManagerId, "endpointManagerId");
        }
    }
}
