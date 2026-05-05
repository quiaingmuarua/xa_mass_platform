package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

/**
 * Shared fixture factory for JDBC contract tests.
 * Keeps H2 and Postgres setup logic in one place so the contract subclasses
 * stay minimal.
 */
final class JdbcContractTestFixture {

    private JdbcContractTestFixture() {
    }

    static HikariDataSource h2DataSource() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("sa");
        config.setPassword("");
        HikariDataSource ds = new HikariDataSource(config);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/control-plane")
                .load()
                .migrate();
        return ds;
    }

    static HikariDataSource postgresDataSource(PostgreSQLContainer<?> container) {
        String database = ("contract_" + UUID.randomUUID())
                .replace('-', '_').toLowerCase();
        try (var conn = DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword())) {
            conn.createStatement().execute("CREATE DATABASE " + database);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create isolated database: " + database, e);
        }

        String url = String.format("jdbc:postgresql://%s:%d/%s",
                container.getHost(), container.getMappedPort(5432), database);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        HikariDataSource ds = new HikariDataSource(config);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/control-plane")
                .load()
                .migrate();
        return ds;
    }
}
