package com.xa.mass.server.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.rules.RuleConfig;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            task.setMaxRuntimeSeconds(1);

            storage.saveTask(task);

            TaskMsg msg = new TaskMsg("msg-1", "task-1", Map.of("target", "x"));
            storage.addTaskMessage("task-1", msg);

            TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-1", "task-1", "msg-1", 1);
            storage.addTaskMessageAttempt("task-1", "msg-1", attempt);

            assertThat(storage.getTask("task-1")).isPresent();
            assertThat(storage.getTasksByStatus(TaskStatus.READY)).hasSize(1);
            assertThat(storage.getTasksByProject("demoApp")).hasSize(1);
            assertThat(storage.getSchedulableTasks()).hasSize(1);
            assertThat(storage.pollExpiredMaxRuntimeTasks(LocalDateTime.now(), 10)).hasSize(1);
            assertThat(storage.countTaskMessages("task-1")).isEqualTo(1);
            assertThat(storage.getTaskMessages("task-1", 1)).hasSize(1);
            assertThat(storage.getNonFinalTaskMessages("task-1")).hasSize(1);
            assertThat(storage.getLatestActiveTaskMessageAttempt("task-1", "msg-1")).isPresent();

            JdbcTaskStorage restartedStorage = new JdbcTaskStorage(fixture.dataSource(), new H2JdbcDialect());
            assertThat(restartedStorage.getTask("task-1")).isPresent();
            assertThat(restartedStorage.countTaskMessages("task-1")).isZero();
            assertThat(restartedStorage.getTaskMessages("task-1")).isEmpty();
            assertThat(restartedStorage.getTaskMessageAttempts("task-1", "msg-1")).isEmpty();

            assertThat(storage.deleteTask("task-1")).isTrue();
        }
    }

    @Test
    void workerStoragePersistsWorkersContextsAndLocks() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcWorkerStorage storage = new JdbcWorkerStorage(fixture.dataSource(), new H2JdbcDialect());
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
            assertThat(storage.findWorkerCandidates("demoApp", "event.demo", null)).hasSize(1);
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
        try (StorageFixture fixture = h2Fixture()) {
            JdbcRuleStorage storage = new JdbcRuleStorage(fixture.dataSource(), new H2JdbcDialect());
            RuleDefinition rule = RuleConfig.getDefaultWorkerMatchRules().getFirst();
            storage.addRule(rule);

            assertThat(storage.getRule(rule.getId())).isPresent();
            assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
            assertThat(storage.getRegisteredEvaluatorTypes()).contains(RuleType.QL_EXPRESS);
            assertThat(storage.deleteRule(rule.getId())).isTrue();
            assertThat(storage.getAllRules()).isEmpty();
        }
    }

    @Test
    void runtimeResidueRecoveryClearsLocksAndOnlineResidue() {
        try (StorageFixture fixture = h2Fixture()) {
            JdbcTaskStorage taskStorage = new JdbcTaskStorage(fixture.dataSource(), new H2JdbcDialect());
            JdbcWorkerStorage workerStorage = new JdbcWorkerStorage(fixture.dataSource(), new H2JdbcDialect());

            Worker worker = new Worker("worker-2", "1.0", List.of("demoApp"));
            worker.setStatus(WorkerStatus.ONLINE);
            workerStorage.addWorker(worker);

            WorkerContext context = new WorkerContext("ctx-2", "worker-2", Set.of("tag-b"));
            context.setProject("demoApp");
            context.setStatus(WorkerContextStatus.OCCUPIED);
            workerStorage.addWorkerContext(context);
            assertThat(workerStorage.tryLockWorker("worker-2")).isTrue();

            Task task = new Task("task-residue", "demo", "demoApp", 1, Map.of(), UserRef.of("u2"));
            task.setStatus(TaskStatus.RUNNING);
            taskStorage.saveTask(task);

            new JdbcRuntimeResidueRecovery().recover(taskStorage, workerStorage);

            assertThat(workerStorage.getWorker("worker-2")).get().extracting(Worker::getStatus).isEqualTo(WorkerStatus.OFFLINE);
            assertThat(workerStorage.getWorkerContextById("ctx-2")).get().extracting(WorkerContext::getStatus).isEqualTo(WorkerContextStatus.IDLE);
            assertThat(workerStorage.isLocked("worker-2")).isFalse();
            assertThat(taskStorage.getTask("task-residue")).get().extracting(Task::getStatus).isEqualTo(TaskStatus.RUNNING);
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
