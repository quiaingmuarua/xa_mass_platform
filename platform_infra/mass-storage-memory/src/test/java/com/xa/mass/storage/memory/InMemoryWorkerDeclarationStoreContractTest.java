package com.xa.mass.storage.memory;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.worker.runtime.contract.WorkerDeclarationStoreContractTest;

class InMemoryWorkerDeclarationStoreContractTest extends WorkerDeclarationStoreContractTest {

    @Override
    protected WorkerDeclarationStore createStorage() {
        return new InMemoryWorkerDeclarationStore();
    }
}
