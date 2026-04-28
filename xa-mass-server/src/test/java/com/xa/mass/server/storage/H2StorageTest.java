package com.xa.mass.server.storage;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
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
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class H2StorageTest {

    @Test
    void taskStoragePersistsTasksMessagesAttemptsAndStats() {
        H2TaskStorage storage = new H2TaskStorage(jdbcUrl(), "sa", "");
        Task task = new Task("task-1", "demo", "demoApp", 1, Map.of("k", "v"), UserRef.of("u1"));
        task.setStatus(TaskStatus.READY);
        task.setStartTime(LocalDateTime.now().minusSeconds(20));
        task.setMaxRuntimeSeconds(1);

        storage.saveTask(task);

        TaskMsg msg = new TaskMsg("msg-1", "task-1", Map.of("target", "x"));
        msg.setStatus(TaskMsgStatus.RUNNING);
        storage.addTaskMessage("task-1", msg);

        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-1", "task-1", "msg-1", 1);
        attempt.setStatus(TaskMsgAttemptStatus.RUNNING);
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
        assertThat(storage.getTaskMessageStats("task-1").getProcessing()).isEqualTo(1);
        assertThat(storage.getTaskMessageAttemptStats("task-1").getRunningAttempts()).isEqualTo(1);

        msg.setStatus(TaskMsgStatus.SUCCESS);
        assertThat(storage.updateTaskMessage("task-1", msg)).isTrue();
        attempt.setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        assertThat(storage.updateTaskMessageAttempt("task-1", "msg-1", attempt)).isTrue();

        assertThat(storage.getNonFinalTaskMessages("task-1")).isEmpty();
        assertThat(storage.getTaskMessageStats("task-1").getSuccess()).isEqualTo(1);
        assertThat(storage.getLatestActiveTaskMessageAttempt("task-1", "msg-1")).isEmpty();
        assertThat(storage.deleteTask("task-1")).isTrue();
    }

    @Test
    void workerStoragePersistsWorkersContextsAndLocks() {
        H2WorkerStorage storage = new H2WorkerStorage(jdbcUrl(), "sa", "");
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

    @Test
    void ruleStoragePersistsRulesButKeepsEvaluatorsInProcess() {
        H2RuleStorage storage = new H2RuleStorage(jdbcUrl(), "sa", "");
        RuleDefinition rule = RuleConfig.getDefaultWorkerMatchRules().getFirst();
        storage.addRule(rule);

        assertThat(storage.getRule(rule.getId())).isPresent();
        assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
        assertThat(storage.getRegisteredEvaluatorTypes()).contains(RuleType.QL_EXPRESS);
        assertThat(storage.deleteRule(rule.getId())).isTrue();
        assertThat(storage.getAllRules()).isEmpty();
    }

    private String jdbcUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    }
}
