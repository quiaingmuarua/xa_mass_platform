package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellLifecycleQueryContractTest;
import org.flywaydb.core.Flyway;

import java.util.UUID;

class JdbcH2TaskShellLifecycleQueryContractTest extends TaskShellLifecycleQueryContractTest {

    private StorageFixture fixture;

    @Override
    protected TaskShellStore createStorage() {
        fixture = h2Fixture();
        return new JdbcTaskShellStore(fixture.dataSource(), new H2JdbcDialect());
    }

    @Override
    protected void destroyStorage(TaskShellStore storage) {
        if (fixture != null) {
            fixture.close();
        }
    }

    private StorageFixture h2Fixture() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false");
        config.setUsername("sa");
        config.setPassword("");
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/control-plane").load().migrate();
        return new StorageFixture(dataSource);
    }

    private record StorageFixture(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
