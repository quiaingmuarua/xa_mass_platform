package com.xa.mass.engine.example;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Demonstrates the active explicit storage wiring truth.
 */
public class StorageFactoryExample {

    private static final Logger log = LoggerFactory.getLogger(StorageFactoryExample.class);

    public static void main(String[] args) {
        testInMemoryStorage();
        testExplicitStorageWiring();
    }

    public static void testInMemoryStorage() {
        log.info("=== testInMemoryStorage ===");

        WorkerStorage workerStorage = new InMemoryWorkerStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();

        Worker worker = new Worker();
        worker.setWorkerId("worker-001");
        worker.setAgentVersion("1.0.0");
        worker.setSupportedProjects(Arrays.asList("demoApp"));
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-001");
        workerContext.setWorkerId("worker-001");
        workerContext.setRoutingTags(java.util.Set.of("us"));
        workerContext.setStatus(WorkerContextStatus.IDLE);

        UserRef user = UserRef.of("testUser");
        Task task = new Task(
                "task-001",
                "Test Task",
                "demoApp",
                100,
                java.util.Map.of("textContent", "Test content", "routingCode", "us"),
                user
        );

        workerStorage.addWorker(worker);
        workerStorage.addWorkerContext(workerContext);
        taskStorage.saveTask(task);

        List<Worker> workers = workerStorage.getAllWorkers();
        Optional<WorkerContext> retrievedCtx = workerStorage.getWorkerContexts("worker-001").stream().findFirst();
        Optional<Task> retrievedTask = taskStorage.getTask("task-001");

        log.info("Workers: {}", workers.size());
        log.info("WorkerContext: {}", retrievedCtx.map(WorkerContext::getWorkerContextId).orElse("null"));
        log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));
        log.info("Rule evaluators: {}", ruleStorage.getRegisteredEvaluatorTypes());
        log.info("=== testInMemoryStorage complete ===");
    }

    public static void testExplicitStorageWiring() {
        log.info("=== testExplicitStorageWiring ===");

        TaskStorage inMemoryStorage = new InMemoryTaskStorage();
        log.info("In-memory storage created: {}", inMemoryStorage.getClass().getSimpleName());

        UserRef user1 = UserRef.of("factoryUser1");
        UserRef user2 = UserRef.of("factoryUser2");

        Task task1 = new Task(
                "task-factory-001",
                "Factory Task 1",
                "demoApp",
                100,
                java.util.Map.of("textContent", "Factory content 1", "routingCode", "us"),
                user1
        );
        Task task2 = new Task(
                "task-factory-002",
                "Factory Task 2",
                "demoApp",
                50,
                java.util.Map.of("textContent", "Factory content 2", "routingCode", "gb"),
                user2
        );

        inMemoryStorage.saveTask(task1);
        inMemoryStorage.saveTask(task2);

        List<Task> pagedTasks = inMemoryStorage.listTasksPaged(0, 100);
        log.info("Tasks in storage: {}", pagedTasks.size());
        for (Task task : pagedTasks) {
            log.info(
                    "Task: {} (routingCode: {}, project: {})",
                    task.getTid(),
                    TaskSharedConfig.routingCode(task),
                    task.getProject()
            );
        }

        log.info("=== testExplicitStorageWiring complete ===");
    }
}
