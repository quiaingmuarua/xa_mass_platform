package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskShellLifecycleQueryContractTest;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPostgresTaskShellLifecycleQueryContractTest extends TaskShellLifecycleQueryContractTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private StorageFixture fixture;

    @Override
    protected TaskShellStore createStorage() {
        fixture = postgresFixture("task_shell_lifecycle_query");
        return new JdbcTaskShellStore(fixture.dataSource(), new PostgresJdbcDialect());
    }

    @Override
    protected void destroyStorage(TaskShellStore storage) {
        if (fixture != null) {
            fixture.close();
        }
    }

    private StorageFixture postgresFixture(String testId) {
        String database = isolatedDatabase(testId);
        String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), database);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        HikariDataSource dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/control-plane").load().migrate();
        return new StorageFixture(dataSource);
    }

    private static String isolatedDatabase(String testId) {
        String database = (testId + "_" + UUID.randomUUID())
                .replace('-', '_').replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.createStatement().execute("CREATE DATABASE " + database);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create isolated database: " + database, e);
        }
        return database;
    }

    private record StorageFixture(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
