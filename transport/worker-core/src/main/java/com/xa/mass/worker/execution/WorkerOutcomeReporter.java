package com.xa.mass.worker.execution;

/** One-shot reporting for the original TASK and Worker run. Host owns retention and cleanup. */
@FunctionalInterface
public interface WorkerOutcomeReporter {
    WorkerOutcomeReporter UNAVAILABLE = (tag, observedAtMillis, payload) -> false;

    /** True means the send was accepted locally, not that Kernel has consumed it. */
    boolean report(int tag, long observedAtMillis, String nullablePayload);
}
