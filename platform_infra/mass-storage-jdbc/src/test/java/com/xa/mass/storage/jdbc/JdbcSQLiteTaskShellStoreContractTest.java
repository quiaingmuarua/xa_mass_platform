package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellStoreContractTest;
import com.zaxxer.hikari.HikariDataSource;

class JdbcSQLiteTaskShellStoreContractTest extends TaskShellStoreContractTest {

    private HikariDataSource dataSource;

    @Override
    protected TaskShellStore createStorage() {
        dataSource = JdbcContractTestFixture.sqliteDataSource();
        return new JdbcTaskShellStore(dataSource, new SQLiteJdbcDialect());
    }

    @Override
    protected void destroyStorage(TaskShellStore storage) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
