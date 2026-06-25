package com.xa.mass.engine.example;

import com.xa.mass.engine.InMemoryWorkerDeclarationRuntimeStore;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.InMemoryTaskShellRuntimeStore;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class EngineExample {

    private static final Logger log = LoggerFactory.getLogger(EngineExample.class);

    public static void main(String[] args) {
        InMemoryTaskShellRuntimeStore taskStorage = new InMemoryTaskShellRuntimeStore();
        TaskManager taskManager = new TaskManager(
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null);
        TaskCommandService taskCommands = new TaskCommandService(taskManager);
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerDeclarationRuntimeStore(),
                new InMemoryWorkerRegistry(),
                new InMemoryWorkerScoreBandSlotRuntime());
        log.info("taskManager:" + taskManager);
        log.info("workerManager:" + workerManager);

        TaskShellCreateRequestDto taskDto = new TaskShellCreateRequestDto();
        taskDto.setSourceRef("demo-task");
        taskDto.setProject("demoApp");
        taskDto.setUserId("demo-user");
        TaskExecutionSpec taskSpec = new TaskExecutionSpec();
        taskSpec.setBatchSize(1);
        taskSpec.setDefaultMaxRetryCount(3);
        taskDto.setExecutionSpec(taskSpec);
        Task task = taskCommands.createTaskShell(taskDto);
        taskCommands.appendTaskItems(task.getTid(), List.of(Map.of("target", "demo-target")));
        taskCommands.sealTask(task.getTid());
    }

    public static Task genMockTask() {
        return null;
    }

}

