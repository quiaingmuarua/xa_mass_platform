package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStorageH2Test {

    @Test
    void taskStoragePersistsTaskTruthButKeepsRuntimeMessageProjectionInProcess() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcTaskStorage storage = new JdbcTaskStorage(fixture.dataSource(), new H2JdbcDialect());
            Task task = new Task("task-1", "demo", "demoApp", 1, Map.of("k", "v"), UserRef.of("u1"));
            task.setStatus(TaskStatus.READY);
            task.setStartTime(LocalDateTime.now().minusSeconds(20));
            task.getExecutionSpec().setMaxRuntimeSeconds(1);

            storage.saveTask(task);

            storage.upsertTaskMessageProjection("task-1", new TaskDetailStore.TaskMessageProjection(
                    "msg-1", "task-1", Map.of("target", "x"), null,
                    TaskMessageProjectionStatus.INIT,
                    null, null, null, null, null,
                    0, 0, null, null, null, null,
                    null, null, null
            ));
            storage.upsertTaskMessageAttemptProjection("task-1", "msg-1", new TaskDetailStore.TaskMessageAttemptProjection(
                    "attempt-1", "task-1", "msg-1", 1,
                    null, null,
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
            assertThat(storage.getTaskMessageProjections("task-1", 1))
                    .allMatch(projection -> projection.status() == null || !projection.status().isFinal());
            assertThat(storage.getLatestActiveTaskMessageAttemptProjection("task-1", "msg-1")).isPresent();

            JdbcTaskStorage restartedStorage = new JdbcTaskStorage(fixture.dataSource(), new H2JdbcDialect());
            assertThat(restartedStorage.getTask("task-1")).isPresent();
            assertThat(restartedStorage.getTaskMessageStats("task-1").getTotal()).isZero();
            assertThat(restartedStorage.getTaskMessageProjections("task-1", 1)).isEmpty();
            assertThat(restartedStorage.getTaskMessageAttemptProjections("task-1", "msg-1")).isEmpty();

            assertThat(storage.deleteTask("task-1")).isTrue();
        }
    }

    @Test
    void ruleStoragePersistsRulesButKeepsEvaluatorsInProcess() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcRuleStorage storage = new JdbcRuleStorage(fixture.dataSource(), new H2JdbcDialect());
            RuleDefinition rule = testRule();
            storage.addRule(rule);

            assertThat(storage.getRule(rule.getId())).isPresent();
            assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
            assertThat(storage.getRegisteredEvaluatorTypes()).contains(RuleType.QL_EXPRESS);
            assertThat(storage.deleteRule(rule.getId())).isTrue();
            assertThat(storage.getAllRules()).isEmpty();
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
