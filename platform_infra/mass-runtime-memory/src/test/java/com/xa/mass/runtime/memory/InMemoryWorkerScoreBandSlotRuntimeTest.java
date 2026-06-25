package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.contract.WorkerScoreBandSlotRuntimeContractTest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;

class InMemoryWorkerScoreBandSlotRuntimeTest extends WorkerScoreBandSlotRuntimeContractTest {

    @Override
    protected WorkerScoreBandSlotRuntime createRuntime() {
        return new InMemoryWorkerScoreBandSlotRuntime();
    }
}
