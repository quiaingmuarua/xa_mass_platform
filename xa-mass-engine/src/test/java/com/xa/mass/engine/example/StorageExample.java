package com.xa.mass.engine.example;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskManagerAssignmentRuntimePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.*;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * StorageExample — demonstrates in-memory worker/workerContext storage and task assignment.
 * Switch to Redis by swapping InMemoryWorkerStorage for RedisWorkerStorage.
 */
public class StorageExample {

    private static final Logger log = LoggerFactory.getLogger(StorageExample.class);

    public static void main(String[] args) {
        testBasicStorage();
        testTaskAssignment();
        testMockAppDefaultConfig();
        testMockAppActualRun();
    }

    public static void testBasicStorage() {
        log.info("=== testBasicStorage ===");

        WorkerStorage workerStorage = new InMemoryWorkerStorage();
        TaskStorage taskStorage = new InMemoryTaskStorage();
        RuleStorage ruleStorage = new InMemoryRuleStorage();

        WorkerManager workerManager = new WorkerManager(workerStorage);
        TaskScheduler taskScheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(taskScheduler, taskStorage);

        Worker worker1 = new Worker();
        worker1.setWorkerId("worker-001");
        worker1.setAgentVersion("1.0.0");
        worker1.setSupportedProjects(Arrays.asList("demoApp"));
        worker1.setWorkerGroupId("us");
        worker1.setStatus(WorkerStatus.ONLINE);

        Worker worker2 = new Worker();
        worker2.setWorkerId("worker-002");
        worker2.setAgentVersion("1.0.1");
        worker2.setSupportedProjects(Arrays.asList("demoApp"));
        worker2.setWorkerGroupId("gb");
        worker2.setStatus(WorkerStatus.ONLINE);

        WorkerContext ctx1 = new WorkerContext();
        ctx1.setWorkerContextId("ctx-001");
        ctx1.setWorkerId("worker-001");
        ctx1.setRoutingTags(java.util.Set.of("us"));
        ctx1.setStatus(WorkerContextStatus.IDLE);

        WorkerContext ctx2 = new WorkerContext();
        ctx2.setWorkerContextId("ctx-002");
        ctx2.setWorkerId("worker-002");
        ctx2.setRoutingTags(java.util.Set.of("gb"));
        ctx2.setStatus(WorkerContextStatus.IDLE);

        workerManager.addWorker(worker1);
        workerManager.addWorker(worker2);
        workerManager.addWorkerContext(ctx1);
        workerManager.addWorkerContext(ctx2);

        List<Worker> allWorkers = workerManager.getAllWorkers();
        List<Worker> usWorkers = workerManager.getWorkersByGroupId("us");
        List<Worker> gbWorkers = workerManager.getWorkersByGroupId("gb");

        log.info("All workers: {}", allWorkers.size());
        log.info("US workers: {}", usWorkers.size());
        log.info("GB workers: {}", gbWorkers.size());

        WorkerContext retrievedCtx1 = workerManager.getWorkerContexts("worker-001").stream().findFirst().orElse(null);
        WorkerContext retrievedCtx2 = workerManager.getWorkerContexts("worker-002").stream().findFirst().orElse(null);

        log.info("WorkerContext1: {}", retrievedCtx1 != null ? retrievedCtx1.getWorkerContextId() : "null");
        log.info("WorkerContext2: {}", retrievedCtx2 != null ? retrievedCtx2.getWorkerContextId() : "null");

        log.info("=== testBasicStorage complete ===");
    }

    public static void testTaskAssignment() {
        log.info("=== testTaskAssignment ===");

        List<Worker> workers = MonkeyGenerator.generateWorkers(MonkeyGenerator.exampleJsonDsl());

        log.info("Generated {} workers", workers.size());

        long usCount = workers.stream()
                .filter(w -> "us".equals(w.getWorkerGroupId()))
                .count();
        long gbCount = workers.stream()
                .filter(w -> "gb".equals(w.getWorkerGroupId()))
                .count();
        log.info("Workers by group - US: {}, GB: {}", usCount, gbCount);

        var workerManager = new WorkerManager();
        for (Worker w : workers) {
            workerManager.addWorker(w);
        }

        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskJson);
        log.info("Generated {} mock tasks", taskDtos.size());

        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var assignmentRuntimePort = new TaskManagerAssignmentRuntimePort(taskManager);
        var msgAssignListener = new SimpleTaskMsgAssignListener(assignmentRuntimePort, workerManager, recordService);
        var workerAssignListener = new TaskWorkerAssignListener(
                ruleManager,
                workerManager,
                msgAssignListener,
                recordService,
                assignmentRuntimePort,
                taskManager.events()
        );

        for (TaskCreateRequestDto dto : taskDtos) {
            String routingCode = routingCode(dto);
            log.info("Testing task: {} (routingCode: {}, project: {})", dto.getTaskName(), routingCode, dto.getProject());

            List<Worker> candidates = workerManager.getWorkersByGroupId(routingCode);
            log.info("Candidates: {}", candidates.size());

            List<Worker> matchedWorkers = new ArrayList<>();
            for (Worker w : candidates) {
                WorkerContext wc = workerManager.getWorkerContexts(w.getWorkerId()).stream().findFirst().orElse(null);
                if (wc != null) {
                    boolean allocatable = wc.isAllocatable();
                    boolean available = wc.isAvailable();
                    boolean usable = wc.isUsable();

                    if (allocatable) {
                        if (workerManager.tryLockWorker(w.getWorkerId())) {
                            matchedWorkers.add(w);
                            log.info("Matched worker: {} (workerContext: {}, status: {})",
                                    w.getWorkerId(), wc.getWorkerContextId(), wc.getStatus());
                        } else {
                            log.info("Worker locked: {}", w.getWorkerId());
                        }
                    } else {
                        log.debug("Worker not eligible: {} (allocatable: {}, available: {}, usable: {})",
                                w.getWorkerId(), allocatable, available, usable);
                    }
                } else {
                    log.debug("Worker has no workerContext: {}", w.getWorkerId());
                }
            }

            log.info("Task {} matched {} workers", dto.getTaskName(), matchedWorkers.size());

            for (Worker w : matchedWorkers) {
                workerManager.unlockWorker(w.getWorkerId());
            }
        }

        log.info("=== testTaskAssignment complete ===");
    }

    public static void testMockAppDefaultConfig() {
        log.info("=== testMockAppDefaultConfig ===");

        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        log.info("Default config: {}", defaultConfig);

        List<Worker> workers = MonkeyGenerator.generateWorkers(defaultConfig);
        log.info("Generated {} workers", workers.size());

        long usCount = workers.stream()
                .filter(w -> "us".equals(w.getWorkerGroupId()))
                .count();
        long gbCount = workers.stream()
                .filter(w -> "gb".equals(w.getWorkerGroupId()))
                .count();
        log.info("Workers by group - US: {}, GB: {}", usCount, gbCount);

        var workerManager = new WorkerManager();
        for (Worker w : workers) {
            workerManager.addWorker(w);
        }

        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskJson);
        log.info("Generated {} mock tasks", taskDtos.size());

        for (TaskCreateRequestDto dto : taskDtos) {
            String routingCode = routingCode(dto);
            log.info("Task: {} (routingCode: {}, project: {})", dto.getTaskName(), routingCode, dto.getProject());

            List<Worker> candidates = workerManager.getWorkersByGroupId(routingCode);
            log.info("Candidates: {}", candidates.size());

            List<Worker> matchedWorkers = new ArrayList<>();
            for (Worker w : candidates) {
                WorkerContext wc = workerManager.getWorkerContexts(w.getWorkerId()).stream().findFirst().orElse(null);
                if (wc != null && wc.isAllocatable()) {
                    if (workerManager.tryLockWorker(w.getWorkerId())) {
                        matchedWorkers.add(w);
                        log.info("Matched: {} (ctx: {}, status: {})", w.getWorkerId(), wc.getWorkerContextId(), wc.getStatus());
                    } else {
                        log.info("Locked: {}", w.getWorkerId());
                    }
                } else {
                    log.debug("Not eligible: {}", w.getWorkerId());
                }
            }

            log.info("Task {} matched {} workers", dto.getTaskName(), matchedWorkers.size());

            for (Worker w : matchedWorkers) {
                workerManager.unlockWorker(w.getWorkerId());
            }
        }

        log.info("=== testMockAppDefaultConfig complete ===");
    }

    public static void testMockAppActualRun() {
        log.info("=== testMockAppActualRun ===");

        var workerManager = new WorkerManager();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var recordService = new AssignmentRecordService();
        var taskManager = new TaskManager(new SimpleTaskScheduler(), new InMemoryTaskStorage());
        var assignmentRuntimePort = new TaskManagerAssignmentRuntimePort(taskManager);
        var msgAssignListener = new SimpleTaskMsgAssignListener(assignmentRuntimePort, workerManager, recordService);
        var workerAssignListener = new TaskWorkerAssignListener(
                ruleManager,
                workerManager,
                msgAssignListener,
                recordService,
                assignmentRuntimePort,
                taskManager.events()
        );

        String defaultConfig = MonkeyGenerator.exampleJsonDsl();
        List<Worker> workers = MonkeyGenerator.generateWorkers(defaultConfig);
        log.info("Generated {} workers", workers.size());

        for (Worker w : workers) {
            workerManager.addWorker(w);
        }

        List<Worker> allWorkers = workerManager.getAllWorkers();
        List<Worker> usWorkers = workerManager.getWorkersByGroupId("us");
        List<Worker> gbWorkers = workerManager.getWorkersByGroupId("gb");

        log.info("Workers - all: {}, US: {}, GB: {}", allWorkers.size(), usWorkers.size(), gbWorkers.size());

        for (int i = 0; i < Math.min(3, allWorkers.size()); i++) {
            Worker w = allWorkers.get(i);
            WorkerContext wc = workerManager.getWorkerContexts(w.getWorkerId()).stream().findFirst().orElse(null);
            log.info("Worker {}: id={}, groupId={}, status={}, ctx={}, ctxStatus={}",
                    i + 1, w.getWorkerId(), w.getWorkerGroupId(), w.getStatus(),
                    wc != null ? wc.getWorkerContextId() : "null",
                    wc != null ? wc.getStatus() : "null");
        }

        String taskJson = MonkeyGenerator.exampleTasksJsonDsl();
        List<TaskCreateRequestDto> taskDtos = MonkeyGenerator.generateTasks(taskJson);
        log.info("Generated {} mock tasks", taskDtos.size());

        for (TaskCreateRequestDto dto : taskDtos) {
            String routingCode = routingCode(dto);
            log.info("Task: {} (routingCode: {}, project: {})", dto.getTaskName(), routingCode, dto.getProject());

            List<Worker> candidates = workerManager.getWorkersByGroupId(routingCode);
            log.info("Candidates: {}", candidates.size());

            List<Worker> matchedWorkers = new ArrayList<>();
            for (Worker w : candidates) {
                WorkerContext wc = workerManager.getWorkerContexts(w.getWorkerId()).stream().findFirst().orElse(null);
                if (wc != null) {
                    boolean allocatable = wc.isAllocatable();
                    boolean available = wc.isAvailable();
                    boolean usable = wc.isUsable();

                    log.debug("Worker {}: ctx={}, status={}, allocatable={}, available={}, usable={}",
                            w.getWorkerId(), wc.getWorkerContextId(), wc.getStatus(), allocatable, available, usable);

                    if (allocatable) {
                        if (workerManager.tryLockWorker(w.getWorkerId())) {
                            matchedWorkers.add(w);
                            log.info("Matched: {} (ctx: {}, status: {})", w.getWorkerId(), wc.getWorkerContextId(), wc.getStatus());
                        } else {
                            log.info("Locked: {}", w.getWorkerId());
                        }
                    } else {
                        log.debug("Not eligible: {} (allocatable: {}, available: {}, usable: {})",
                                w.getWorkerId(), allocatable, available, usable);
                    }
                } else {
                    log.debug("No workerContext: {}", w.getWorkerId());
                }
            }

            log.info("Task {} matched {} workers", dto.getTaskName(), matchedWorkers.size());

            for (Worker w : matchedWorkers) {
                workerManager.unlockWorker(w.getWorkerId());
            }
        }

        log.info("=== testMockAppActualRun complete ===");
    }

    private static String routingCode(TaskCreateRequestDto dto) {
        return TaskSharedConfig.stringValue(
                dto != null ? dto.getSharedConfig() : Map.of(),
                TaskSharedConfig.ROUTING_CODE
        );
    }
}
