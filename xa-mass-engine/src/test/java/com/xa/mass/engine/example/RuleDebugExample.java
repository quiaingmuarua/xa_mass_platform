package com.xa.mass.engine.example;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.storage.WorkerStorage;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simple debugging entry for rule-based worker matching.
 */
public class RuleDebugExample {

    private static final Logger logger = LoggerFactory.getLogger(RuleDebugExample.class);

    public static void main(String[] args) {
        System.out.println("=== Rule Debug Example ===");

        WorkerStorage workerStorage = TaskStorageFactory.createDefaultWorkerStorage();
        WorkerManager workerManager = new WorkerManager(workerStorage);
        RuleManager<Map<String, Object>> ruleManager = RuleManagerFactory.getDefaultRuleManager();

        generateTestData(workerManager);
        Task testTask = createTestTask();

        List<Worker> candidates = workerManager.getAllWorkers();
        System.out.println("Candidate worker count: " + candidates.size());

        for (Worker worker : candidates) {
            debugWorkerEvaluation(worker, testTask, workerManager, ruleManager);
        }
    }

    private static void generateTestData(WorkerManager workerManager) {
        String[] countries = {"us", "gb", "ca"};

        for (int i = 0; i < 10; i++) {
            String country = countries[i % countries.length];

            Worker worker = new Worker();
            worker.setWorkerId("worker-" + i);
            worker.setWorkerGroupId(country);
            worker.setStatus(WorkerStatus.ONLINE);
            worker.setAgentVersion("1.0." + (i % 5));
            worker.setSupportedProjects(Arrays.asList("demoApp"));

            WorkerContext workerContext = new WorkerContext();
            workerContext.setWorkerContextId("ctx-" + i);
            workerContext.setWorkerId(worker.getWorkerId());
            workerContext.setStatus(WorkerContextStatus.IDLE);
            workerContext.setRoutingTags(java.util.Set.of(country));

            workerManager.addWorker(worker);
            workerManager.addWorkerContext(workerContext);

            logger.info("Worker {} supports projects: {}", worker.getWorkerId(),
                    String.join(", ", worker.getSupportedProjects()));
        }

        System.out.println("Generated 10 test workers and workerContexts");
    }

    private static Task createTestTask() {
        Task task = new Task();
        task.setTid("test-task-001");
        task.setTaskName("routing-code-debug");
        task.setProject("demoApp");
        task.setTaskRoutingCode("us");
        task.setStatus(TaskStatus.READY);
        task.setTaskTargetNumber(100);
        task.setBatchSize(10);
        task.setMinRequiredWorkerCount(5);
        return task;
    }

    private static void debugWorkerEvaluation(Worker worker, Task task, WorkerManager workerManager,
                                              RuleManager<Map<String, Object>> ruleManager) {
        System.out.println("\n=== Debugging worker: " + worker.getWorkerId() + " ===");

        WorkerContext workerContext = workerManager.getWorkerContexts(worker.getWorkerId()).stream().findFirst().orElse(null);
        WorkerMatchContext matchContext = new WorkerMatchContext(worker, workerContext, task, workerManager);

        System.out.println("Worker:");
        System.out.println("  - id: " + worker.getWorkerId());
        System.out.println("  - workerGroupId: " + worker.getWorkerGroupId());
        System.out.println("  - status: " + worker.getStatus());
        System.out.println("  - agentVersion: " + worker.getAgentVersion());
        System.out.println("  - supportedProjects: " + worker.getSupportedProjects());

        if (workerContext != null) {
            System.out.println("WorkerContext:");
            System.out.println("  - id: " + workerContext.getWorkerContextId());
            System.out.println("  - status: " + workerContext.getStatus());
            System.out.println("  - routingTags: " + workerContext.getRoutingTags());
            System.out.println("  - attributes: " + workerContext.getAttributes());
        } else {
            System.out.println("WorkerContext: null");
        }

        System.out.println("Task:");
        System.out.println("  - id: " + task.getTid());
        System.out.println("  - project: " + task.getProject());
        System.out.println("  - routingCode: " + task.getTaskRoutingCode());

        Map<String, Object> context = matchContext.getContext();
        System.out.println("Computed context:");
        System.out.println("  - appCount: " + context.get("appCount"));
        System.out.println("  - supportsProject: " + context.get("supportsProject"));
        System.out.println("  - workerContextMatchesRoutingCode: " + context.get("workerContextMatchesRoutingCode"));

        List<RuleDefinition> rules = ruleManager.getDefaultRules();
        System.out.println("\nRule evaluation:");

        int passedRules = 0;
        for (RuleDefinition rule : rules) {
            try {
                boolean result = ruleManager.evaluate(rule, context);
                System.out.println("  - " + rule.getId() + " (" + rule.getDesc() + "): " + (result ? "PASS" : "FAIL"));
                System.out.println("    expression: " + rule.getContent());
                if (result) {
                    passedRules++;
                }
            } catch (Exception e) {
                System.out.println("  - " + rule.getId() + ": ERROR - " + e.getMessage());
            }
        }

        System.out.println("Summary: " + passedRules + "/" + rules.size() + " rules passed");
    }
}
