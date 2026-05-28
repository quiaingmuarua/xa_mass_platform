package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.WorkerDeclarationStore;
import com.xa.mass.storage.contract.WorkerDeclarationStoreContractTest;

class InMemoryWorkerDeclarationStoreContractTest extends WorkerDeclarationStoreContractTest {

    @Override
    protected WorkerDeclarationStore createStorage() {
        return new InMemoryWorkerDeclarationStore();
    }
}
