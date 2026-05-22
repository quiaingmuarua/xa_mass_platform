package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.contract.TaskWorkRuntimeContractTest;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

class InMemoryTaskWorkRuntimeContractTest extends TaskWorkRuntimeContractTest {

    @Override
    protected TaskWorkRuntime createRuntime(AtomicReference<Instant> clock) {
        return new InMemoryTaskWorkRuntime(1024, clock::get);
    }
}
