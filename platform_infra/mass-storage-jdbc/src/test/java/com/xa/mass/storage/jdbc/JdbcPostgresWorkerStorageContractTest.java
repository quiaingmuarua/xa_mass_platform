package com.xa.mass.storage.jdbc;

import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.contract.WorkerStorageContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DisabledIfSystemProperty(named = "skip.docker.tests", matches = "true")
class JdbcPostgresWorkerStorageContractTest extends WorkerStorageContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;

    @Override
    protected WorkerStorage createStorage() {
        dataSource = JdbcContractTestFixture.postgresDataSource(POSTGRES);
        return new JdbcWorkerStorage(dataSource, new PostgresJdbcDialect());
    }

    @Override
    protected void destroyStorage(WorkerStorage storage) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
