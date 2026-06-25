package com.xa.mass.worker.runtime.admission;

import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;

import java.util.List;

/**
 * Narrow worker-runtime admission support surface.
 *
 * <p>Score-band selection owns claim and release state. This interface only
 * exposes the remaining exclusive-lock and load-read support used by
 * worker-runtime selection and diagnostics.</p>
 */
public interface WorkerAdmissionRuntime {

    boolean tryAcquireWorkerExclusiveLease(String workerId);

    void releaseWorkerExclusiveLease(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    List<String> getExclusiveLeaseWorkerIds();

    WorkerLoadSnapshot getWorkerLoad(String workerId);
}
