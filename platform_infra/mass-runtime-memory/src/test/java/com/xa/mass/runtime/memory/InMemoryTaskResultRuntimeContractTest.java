package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.contract.TaskResultRuntimeContractTest;

class InMemoryTaskResultRuntimeContractTest extends TaskResultRuntimeContractTest {

    @Override
    protected TaskResultRuntime createRuntime() {
        return new InMemoryTaskResultRuntime(25L);
    }
}
