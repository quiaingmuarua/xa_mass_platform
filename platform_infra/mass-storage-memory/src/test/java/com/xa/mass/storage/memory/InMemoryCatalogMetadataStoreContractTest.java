package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.contract.CatalogMetadataStoreContractTest;

class InMemoryCatalogMetadataStoreContractTest extends CatalogMetadataStoreContractTest {

    @Override
    protected CatalogMetadataStore createStorage() {
        return new InMemoryCatalogMetadataStore();
    }
}
