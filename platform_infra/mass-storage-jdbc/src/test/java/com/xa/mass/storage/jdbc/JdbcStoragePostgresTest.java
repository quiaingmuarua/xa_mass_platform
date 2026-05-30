package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcStoragePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void taskStoragePersistsTaskShellTruthOnly() {
        try (StorageFixture fixture = postgresFixture("task_storage")) {
            JdbcTaskShellStore storage = new JdbcTaskShellStore(fixture.dataSource(), new PostgresJdbcDialect());
            Task task = new Task("task-1", "demo", "demoApp", 1, Map.of("k", "v"), UserRef.of("u1"));
            task.setStatus(TaskStatus.READY);
            task.setStartTime(LocalDateTime.now().minusSeconds(20));
            task.getExecutionSpec().setMaxRuntimeSeconds(1);

            storage.saveTask(task);

            assertThat(storage.getTask("task-1")).isPresent();
            assertThat(storage.getTasksByStatus(TaskStatus.READY)).hasSize(1);
            assertThat(storage.getTasksByProject("demoApp")).hasSize(1);
            assertThat(storage.pollTasksPastMaxRuntimeDeadline(LocalDateTime.now(), 10)).hasSize(1);

            JdbcTaskShellStore restartedStorage = new JdbcTaskShellStore(fixture.dataSource(), new PostgresJdbcDialect());
            assertThat(restartedStorage.getTask("task-1")).isPresent();

            assertThat(storage.deleteTask("task-1")).isTrue();
        }
    }

    @Test
    void ruleStoragePersistsRulesAsDefinitionStore() {
        try (StorageFixture fixture = postgresFixture("rule_storage")) {
            JdbcRuleStorage storage = new JdbcRuleStorage(fixture.dataSource(), new PostgresJdbcDialect());
            RuleDefinition rule = testRule();
            storage.addRule(rule);

            assertThat(storage.getRule(rule.getId())).isPresent();
            assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
            assertThat(storage.deleteRule(rule.getId())).isTrue();
            assertThat(storage.getAllRules()).isEmpty();
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

    private RuleDefinition testRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("basic_worker_check");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        rule.setDescription("Worker must be available and unlocked");
        return rule;
    }

    private record StorageFixture(HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
