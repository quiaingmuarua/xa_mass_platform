package com.xa.mass.engine.example;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
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
        TaskManager taskManager = new TaskManager(
                new SimpleTaskScheduler(),
                new InMemoryTaskStorage(),
                new InMemoryTaskWorkRuntime());
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        log.info("taskManager:" + taskManager);
        log.info("workerManager:" + workerManager);

        List<Worker> workers = genMockWorker();
        workers.forEach(workerManager::addWorker);

        List<WorkerContext> workerContexts = genMockWorkerContext();
        workerContexts.forEach(workerManager::addWorkerContext);

        TaskCreateRequestDto taskDto = new TaskCreateRequestDto();
        taskManager.createTask(taskDto);
    }

    public static Task genMockTask() {
        return null;
    }

    public static List<WorkerContext> genMockWorkerContext() {
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
