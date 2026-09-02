package com.xa.mass.kernel.pacer.dispatch;

record WorkerCandidateRequest(
        int priority,
        int requestedCount
) {
    public WorkerCandidateRequest {
        if (priority < 0 || priority > 99) {
            throw new IllegalArgumentException(
                    "candidate priority must be in 0..99"
            );
        }
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(
                    "requested candidate count must be positive"
            );
        }
    }
}
