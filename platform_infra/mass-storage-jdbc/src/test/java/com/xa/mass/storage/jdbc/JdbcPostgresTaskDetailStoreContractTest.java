package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskDetailStoreContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
@DisabledIfSystemProperty(named = "skip.docker.tests", matches = "true")
class JdbcPostgresTaskDetailStoreContractTest extends TaskDetailStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private HikariDataSource dataSource;
    private JdbcTaskShellStore taskStorage;

    @Override
    protected TaskDetailStore createStore() {
        dataSource = JdbcContractTestFixture.postgresDataSource(POSTGRES);
        taskStorage = new JdbcTaskShellStore(dataSource, new PostgresJdbcDialect());
        return taskStorage;
    }

    @Override
    protected void initTask(String taskId) {
        Task task = new Task(taskId, "name", "demoApp", 1, Map.of(), UserRef.of("u"));
        task.setStatus(TaskStatus.RUNNING);
        taskStorage.saveTask(task);
    }

    @Override
    protected void destroyStore(TaskDetailStore store) {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
