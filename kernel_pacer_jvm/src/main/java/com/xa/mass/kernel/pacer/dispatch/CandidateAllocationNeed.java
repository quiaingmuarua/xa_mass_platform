package com.xa.mass.kernel.pacer.dispatch;

/** Static Task coordinates required to refill one candidate bucket. */
record CandidateAllocationNeed(
        String workerGroupId,
        String candidateId,
        int priority,
        int maximumCandidateWorkers
) {
}
