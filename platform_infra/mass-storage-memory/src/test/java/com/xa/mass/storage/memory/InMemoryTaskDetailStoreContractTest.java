package com.xa.mass.storage.memory;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.storage.contract.TaskDetailStoreContractTest;

import java.util.Map;

class InMemoryTaskDetailStoreContractTest extends TaskDetailStoreContractTest {

    @Override
    protected TaskDetailStore createStore() {
        return new InMemoryTaskShellStore();
    }

    @Override
    protected void initTask(String taskId) {
        Task task = new Task(taskId, "name", "demoApp", 1, Map.of(), UserRef.of("u"));
        task.setStatus(TaskStatus.RUNNING);
        ((TaskShellStore) store).saveTask(task);
    }
}
