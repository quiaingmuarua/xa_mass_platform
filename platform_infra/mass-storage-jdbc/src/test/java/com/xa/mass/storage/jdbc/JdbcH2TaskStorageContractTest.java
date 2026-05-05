package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.contract.TaskStorageContractTest;
import com.zaxxer.hikari.HikariDataSource;

class JdbcH2TaskStorageContractTest extends TaskStorageContractTest {

    private HikariDataSource dataSource;

    @Override
    protected TaskStorage createStorage() {
        dataSource = JdbcContractTestFixture.h2DataSource();
        return new JdbcTaskStorage(dataSource, new H2JdbcDialect());
    }

    @Override
    protected void destroyStorage(TaskStorage storage) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
