package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.contract.WorkerStorageContractTest;
import com.zaxxer.hikari.HikariDataSource;

class JdbcH2WorkerStorageContractTest extends WorkerStorageContractTest {

    private HikariDataSource dataSource;

    @Override
    protected WorkerStorage createStorage() {
        dataSource = JdbcContractTestFixture.h2DataSource();
        return new JdbcWorkerStorage(dataSource, new H2JdbcDialect());
    }

    @Override
    protected void destroyStorage(WorkerStorage storage) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
