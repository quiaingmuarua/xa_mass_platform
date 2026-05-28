package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellLifecycleQueryContractTest;

class InMemoryTaskShellLifecycleQueryContractTest extends TaskShellLifecycleQueryContractTest {

    @Override
    protected TaskShellStore createStorage() {
        return new InMemoryTaskShellStore();
    }
}
