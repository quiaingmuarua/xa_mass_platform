package com.xa.mass.engine.example;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EngineExample {

    private static final Logger log = LoggerFactory.getLogger(EngineExample.class);

    public static void main(String[] args) {
        InMemoryTaskShellStore taskStorage = new InMemoryTaskShellStore();
        TaskManager taskManager = new TaskManager(
                taskStorage,
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null);
        TaskCommandService taskCommands = new TaskCommandService(taskManager);
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
        log.info("taskManager:" + taskManager);
        log.info("workerManager:" + workerManager);

        List<Worker> workers = genMockWorker();
        workers.forEach(workerManager::addWorker);

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

    public static List<Worker> genMockWorker() {
        JsonDslDefinition definition = new JsonDslDefinition("worker_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("Generate 300 mock workers");
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("workerId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("workerGroupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), Worker.class);
    }
}

