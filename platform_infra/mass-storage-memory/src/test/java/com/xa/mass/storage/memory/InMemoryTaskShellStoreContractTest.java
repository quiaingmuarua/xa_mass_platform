package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellStoreContractTest;

class InMemoryTaskShellStoreContractTest extends TaskShellStoreContractTest {

    @Override
    protected TaskShellStore createStorage() {
        return new InMemoryTaskShellStore();
    }
}
