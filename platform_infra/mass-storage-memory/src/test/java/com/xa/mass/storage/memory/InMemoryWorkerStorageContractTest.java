package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.contract.WorkerStorageContractTest;

class InMemoryWorkerStorageContractTest extends WorkerStorageContractTest {

    @Override
    protected WorkerStorage createStorage() {
        return new InMemoryWorkerStorage();
    }
}
