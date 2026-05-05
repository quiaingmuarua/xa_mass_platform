package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.contract.TaskStorageContractTest;

class InMemoryTaskStorageContractTest extends TaskStorageContractTest {

    @Override
    protected TaskStorage createStorage() {
        return new InMemoryTaskStorage();
    }
}
