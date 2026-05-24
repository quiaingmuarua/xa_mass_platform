package com.xa.mass.engine.worker;

import java.util.List;

/**
 * Selects a bounded worker-id sample from a group/route bucket.
 */
public interface WorkerCandidateSamplingPolicy {

    List<String> sample(WorkerCandidateSamplingContext context,
                        List<String> workerIds,
                        int maxCandidateCount);
}
