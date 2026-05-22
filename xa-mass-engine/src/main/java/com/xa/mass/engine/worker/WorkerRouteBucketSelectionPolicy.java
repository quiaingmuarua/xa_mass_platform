package com.xa.mass.engine.worker;

import java.util.List;

/**
 * Selects a bounded worker-id sample from a route bucket before Stage-2
 * eligibility, ranking, and resource admission.
 */
public interface WorkerRouteBucketSelectionPolicy {

    List<String> select(WorkerRouteBucketSelectionContext context,
                        List<String> workerIds,
                        int maxCandidateCount);
}
