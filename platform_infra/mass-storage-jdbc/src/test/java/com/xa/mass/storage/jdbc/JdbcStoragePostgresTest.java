package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class JdbcStoragePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void taskStoragePersistsTaskTruthButKeepsRuntimeMessageProjectionInProcess() {
        try (StorageFixture fixture = postgresFixture("task_storage")) {
            JdbcTaskStorage storage = new JdbcTaskStorage(fixture.dataSource(), new PostgresJdbcDialect());
            Task task = new Task("task-1", "demo", "demoApp", 1, Map.of("k", "v"), UserRef.of("u1"));
            task.setStatus(TaskStatus.READY);
            task.setStartTime(LocalDateTime.now().minusSeconds(20));
            task.setMaxRuntimeSeconds(1);

            storage.saveTask(task);

            storage.upsertTaskMessageProjection("task-1", new TaskDetailStore.TaskMessageProjection(
                    "msg-1", "task-1", Map.of("target", "x"), null,
                    TaskMessageProjectionStatus.INIT,
                    null, null, null, null, null,
                    0, 0, null, null, null, null,
                    null, null, null, null
            ));
            storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", new TaskDetailStore.TaskMessageAttemptProjection(
                    "attempt-1", "task-1", "msg-1", 1,
                    null, null, null,
                    TaskMessageAttemptProjectionStatus.DISPATCHED,
                    null, null, null, null
            ));

            assertThat(storage.getTask("task-1")).isPresent();
            assertThat(storage.getTasksByStatus(TaskStatus.READY)).hasSize(1);
            assertThat(storage.getTasksByProject("demoApp")).hasSize(1);
            assertThat(storage.getSchedulableTasks()).hasSize(1);
            assertThat(storage.pollExpiredMaxRuntimeTasks(LocalDateTime.now(), 10)).hasSize(1);
            assertThat(storage.getTaskMessageStats("task-1").getTotal()).isEqualTo(1);
            assertThat(storage.getTaskMessageProjections("task-1", 1)).hasSize(1);
            assertThat(storage.getTaskMessageProjections("task-1"))
                    .allMatch(projection -> projection.status() == null || !projection.status().isFinal());
            assertThat(storage.getLatestActiveTaskMessageAttemptProjection("task-1", "msg-1")).isPresent();

            JdbcTaskStorage restartedStorage = new JdbcTaskStorage(fixture.dataSource(), new PostgresJdbcDialect());
            assertThat(restartedStorage.getTask("task-1")).isPresent();
            assertThat(restartedStorage.getTaskMessageStats("task-1").getTotal()).isZero();
            assertThat(restartedStorage.getTaskMessageProjections("task-1")).isEmpty();
            assertThat(restartedStorage.getTaskMessageAttemptProjections("task-1", "msg-1")).isEmpty();

            assertThat(storage.deleteTask("task-1")).isTrue();
        }
    }

    @Test
    void workerStoragePersistsWorkersContextsAndLocks() {
        try (StorageFixture fixture = postgresFixture("worker_storage")) {
            JdbcWorkerStorage storage = new JdbcWorkerStorage(fixture.dataSource(), new PostgresJdbcDialect());
            Worker worker = new Worker("worker-1", "1.0", List.of("demoApp"));
            worker.setSupportedEventCodes(List.of("event.demo"));
            worker.setWorkerGroupId("group-a");
            worker.setStatus(WorkerStatus.ONLINE);
            storage.addWorker(worker);

            WorkerContext context = new WorkerContext("ctx-1", "worker-1", Set.of("tag-a"));
            context.setProject("demoApp");
            context.setStatus(WorkerContextStatus.IDLE);
            storage.addWorkerContext(context);

            assertThat(storage.getWorker("worker-1")).isPresent();
            assertThat(storage.getWorkersByGroupId("group-a")).hasSize(1);
            assertThat(storage.getWorkersBySupportedProject("demoApp")).hasSize(1);
            assertThat(storage.getWorkersBySupportedEventCode("event.demo")).hasSize(1);
            assertThat(storage.getWorkerContexts("worker-1")).hasSize(1);
            assertThat(storage.getWorkerContextById("ctx-1")).isPresent();
            assertThat(storage.tryLockWorker("worker-1")).isTrue();
            assertThat(storage.tryLockWorker("worker-1")).isFalse();
            assertThat(storage.isLocked("worker-1")).isTrue();
            storage.unlockWorker("worker-1");
            assertThat(storage.isLocked("worker-1")).isFalse();
        }
    }

    @Test
    void ruleStoragePersistsRulesButKeepsEvaluatorsInProcess() {
        try (StorageFixture fixture = postgresFixture("rule_storage")) {
            JdbcRuleStorage storage = new JdbcRuleStorage(fixture.dataSource(), new PostgresJdbcDialect());
            RuleDefinition rule = testRule();
            storage.addRule(rule);

            assertThat(storage.getRule(rule.getId())).isPresent();
            assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
            assertThat(storage.getRegisteredEvaluatorTypes()).contains(RuleType.QL_EXPRESS);
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
