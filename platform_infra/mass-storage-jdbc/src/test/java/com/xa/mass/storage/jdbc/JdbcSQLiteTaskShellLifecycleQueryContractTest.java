package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellLifecycleQueryContractTest;
import com.zaxxer.hikari.HikariDataSource;

class JdbcSQLiteTaskShellLifecycleQueryContractTest extends TaskShellLifecycleQueryContractTest {

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
