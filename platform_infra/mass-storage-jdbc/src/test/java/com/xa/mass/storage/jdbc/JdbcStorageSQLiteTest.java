package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStorageSQLiteTest {

    @Test
    void runtimeCreatesSqliteFileAndPersistsTaskRuleTruth() throws Exception {
        var db = Files.createTempDirectory("xa-mass-sqlite-runtime").resolve("xa_mass.db");
        try (JdbcStorageRuntime runtime = JdbcStorageRuntime.create(
                JdbcStorageMode.JDBC_SQLITE,
                "jdbc:sqlite:" + db,
                "",
                "")) {
            assertThat(runtime.isEnabled()).isTrue();
            assertThat(Files.exists(db)).isTrue();

            Task task = new Task("task-sqlite-1", "demo", "demoApp", 1, Map.of("k", "v"), UserRef.of("u1"));
            task.setStatus(TaskStatus.READY);
            task.setCreateTime(LocalDateTime.of(2026, 1, 1, 12, 0));
            task.setStartTime(LocalDateTime.now().minusSeconds(20));
            task.getExecutionSpec().setMaxRuntimeSeconds(1);

            runtime.taskShellStore().saveTask(task);
            assertThat(runtime.taskShellStore().getTask("task-sqlite-1")).isPresent();
            assertThat(runtime.taskShellStore().listTasksPaged(0, 10))
                    .extracting(Task::getTid)
                    .containsExactly("task-sqlite-1");

            RuleDefinition rule = testRule();
            runtime.ruleStorage().addRule(rule);
            assertThat(runtime.ruleStorage().getRule(rule.getId())).isPresent();
        }
    }

    private RuleDefinition testRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("sqlite_worker_check");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        rule.setDescription("Worker must be available and unlocked");
        return rule;
    }
}
