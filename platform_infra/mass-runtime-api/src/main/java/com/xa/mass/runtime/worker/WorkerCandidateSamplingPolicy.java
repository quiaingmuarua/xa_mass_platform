package com.xa.mass.runtime.worker;

import java.util.List;

/**
 * Selects a bounded worker-id sample from a group/candidate bucket.
 *
 * <p>The {@code workerIds} input is an implementation-provided source batch.
 * It may be a complete in-memory bucket or a bounded Redis-side subset. Policy
 * implementations must not depend on seeing every bucket member; Stage-2
 * source guards and reserve remain the correctness boundary.</p>
 */
public interface WorkerCandidateSamplingPolicy {

    List<String> sample(WorkerCandidateSamplingContext context,
                        List<String> workerIds,
                        int maxCandidateCount);
}
