package com.xa.mass.engine.example;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.User;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.storage.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * StorageFactoryExample — demonstrates how to use TaskStorageFactory to create different storage types.
 * Switch to Redis by changing the factory call.
 */
public class StorageFactoryExample {

    private static final Logger log = LoggerFactory.getLogger(StorageFactoryExample.class);

    public static void main(String[] args) {
        testInMemoryStorage();
        testRedisStorage();
        testStorageFactory();
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

        User user = new User();
        user.setName("testUser");

        Task task = new Task("task-001", "Test Task", "demoApp", "us", 100, java.util.Map.of("textContent", "Test content"), user);

        workerStorage.addWorker(worker);
        workerStorage.addWorkerContext(workerContext);
        taskStorage.saveTask(task);

        List<Worker> workers = workerStorage.getAllWorkers();
        Optional<WorkerContext> retrievedCtx = workerStorage.getWorkerContexts("worker-001").stream().findFirst();
        Optional<Task> retrievedTask = taskStorage.getTask("task-001");

        log.info("Workers: {}", workers.size());
        log.info("WorkerContext: {}", retrievedCtx.map(WorkerContext::getWorkerContextId).orElse("null"));
        log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));

        log.info("=== testInMemoryStorage complete ===");
    }

    public static void testRedisStorage() {
        log.info("=== testRedisStorage ===");

        try {
            WorkerStorage workerStorage = new RedisWorkerStorage();
            TaskStorage taskStorage = new RedisTaskStorage();
            RuleStorage ruleStorage = new RedisRuleStorage();

            log.info("Redis storage instances created");

            Worker worker = new Worker();
            worker.setWorkerId("worker-002");
            worker.setAgentVersion("1.0.1");
            worker.setSupportedProjects(Arrays.asList("demoApp"));
            worker.setWorkerGroupId("gb");

            WorkerContext workerContext = new WorkerContext();
            workerContext.setWorkerContextId("ctx-002");
            workerContext.setWorkerId("worker-002");
            workerContext.setRoutingTags(java.util.Set.of("gb"));
            workerContext.setStatus(WorkerContextStatus.IDLE);

            User user = new User();
            user.setName("testUser2");

            Task task = new Task("task-002", "Test Task 2", "demoApp", "gb", 50, java.util.Map.of("textContent", "Test content 2"), user);

            workerStorage.addWorker(worker);
            workerStorage.addWorkerContext(workerContext);
            taskStorage.saveTask(task);

            List<Worker> workers = workerStorage.getAllWorkers();
            Optional<WorkerContext> retrievedCtx = workerStorage.getWorkerContexts("worker-002").stream().findFirst();
            Optional<Task> retrievedTask = taskStorage.getTask("task-002");

            log.info("Workers: {}", workers.size());
            log.info("WorkerContext: {}", retrievedCtx.map(WorkerContext::getWorkerContextId).orElse("null"));
            log.info("Task: {}", retrievedTask.map(Task::getTid).orElse("null"));

        } catch (Exception e) {
            log.warn("Redis storage test failed — Redis server may not be running: {}", e.getMessage());
        }

        log.info("=== testRedisStorage complete ===");
    }

    public static void testStorageFactory() {
        log.info("=== testStorageFactory ===");

        TaskStorage inMemoryStorage = TaskStorageFactory.createStorage("memory");
        log.info("In-memory storage created: {}", inMemoryStorage.getClass().getSimpleName());

        try {
            TaskStorage redisStorage = TaskStorageFactory.createStorage("redis");
            log.info("Redis storage created: {}", redisStorage.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Redis storage creation failed: {}", e.getMessage());
        }

        try {
            TaskStorage unknownStorage = TaskStorageFactory.createStorage("unknown");
        } catch (IllegalArgumentException e) {
            log.info("Unknown storage type correctly rejected: {}", e.getMessage());
        }

        User user1 = new User();
        user1.setName("factoryUser1");
        User user2 = new User();
        user2.setName("factoryUser2");

        Task task1 = new Task("task-factory-001", "Factory Task 1", "demoApp", "us", 100, java.util.Map.of("textContent", "Factory content 1"), user1);
        Task task2 = new Task("task-factory-002", "Factory Task 2", "demoApp", "gb", 50, java.util.Map.of("textContent", "Factory content 2"), user2);

        inMemoryStorage.saveTask(task1);
        inMemoryStorage.saveTask(task2);

        List<Task> allTasks = inMemoryStorage.getAllTasks();
        log.info("Tasks in storage: {}", allTasks.size());

        for (Task task : allTasks) {
            log.info("Task: {} (routingCode: {}, project: {})", task.getTid(), task.getTaskRoutingCode(), task.getProject());
        }

        log.info("=== testStorageFactory complete ===");
    }
}
