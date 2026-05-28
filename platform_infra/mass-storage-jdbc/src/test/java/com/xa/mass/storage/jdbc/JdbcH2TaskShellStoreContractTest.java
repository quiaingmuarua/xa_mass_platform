package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellStoreContractTest;
import com.zaxxer.hikari.HikariDataSource;

class JdbcH2TaskShellStoreContractTest extends TaskShellStoreContractTest {

    private HikariDataSource dataSource;

    @Override
    protected TaskShellStore createStorage() {
        dataSource = JdbcContractTestFixture.h2DataSource();
        return new JdbcTaskShellStore(dataSource, new H2JdbcDialect());
    }

    @Override
    protected void destroyStorage(TaskShellStore storage) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
