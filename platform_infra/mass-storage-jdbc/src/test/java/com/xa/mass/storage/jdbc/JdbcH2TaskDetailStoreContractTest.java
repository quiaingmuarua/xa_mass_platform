package com.xa.mass.storage.jdbc;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskDetailStoreContractTest;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Map;

class JdbcH2TaskDetailStoreContractTest extends TaskDetailStoreContractTest {

    private HikariDataSource dataSource;
    private JdbcTaskShellStore taskStorage;

    @Override
    protected TaskDetailStore createStore() {
        dataSource = JdbcContractTestFixture.h2DataSource();
        taskStorage = new JdbcTaskShellStore(dataSource, new H2JdbcDialect());
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
