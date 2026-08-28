package com.xa.mass.kernel.pacer.result;

import com.xa.mass.kernel.worker.WorkerLeaseReference;

record WorkerResultEvidence(
        String workerId,
        WorkerLeaseReference workerLease
) {
}
