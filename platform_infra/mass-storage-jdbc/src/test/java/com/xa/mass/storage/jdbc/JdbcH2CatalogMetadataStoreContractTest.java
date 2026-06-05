package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.contract.CatalogMetadataStoreContractTest;

class JdbcH2CatalogMetadataStoreContractTest extends CatalogMetadataStoreContractTest {

    private HikariDataSource dataSource;

    @Override
    protected CatalogMetadataStore createStorage() {
        dataSource = JdbcContractTestFixture.h2DataSource();
        return new JdbcCatalogMetadataStore(dataSource, new H2JdbcDialect());
    }

    @Override
    protected void destroyStorage(CatalogMetadataStore storage) {
        dataSource.close();
    }
}
