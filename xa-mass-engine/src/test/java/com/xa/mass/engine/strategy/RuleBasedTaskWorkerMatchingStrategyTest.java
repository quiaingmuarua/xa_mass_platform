package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.storage.InMemoryWorkerStorage;
import com.xa.mass.engine.storage.InMemoryRuleStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedTaskWorkerMatchingStrategyTest {

    @Test
    void matchesWorkerUsingWorkerContextAttributesRule() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("workerContext_status_check", "isWorkerContextAllocatable == true && isWorkerContextAvailable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("workerContext_attribute_country", "workerContextAttributes['country'] == taskRoutingCountryCode")
        ));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject("demoApp");
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        Worker matchingWorker = worker("worker-us", "pool-east");
        Worker nonMatchingWorker = worker("worker-gb", "pool-west");
        workerManager.addWorker(matchingWorker);
        workerManager.addWorker(nonMatchingWorker);

        workerManager.addWorkerContext(matchingWorker.getWorkerId(), workerContext("worker-us", "ctx-us", "shared", "us"));
        workerManager.addWorkerContext(nonMatchingWorker.getWorkerId(), workerContext("worker-gb", "ctx-gb", "shared", "gb"));

        List<MatchedWorkerContext> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-us", matched.get(0).getWorkerId());
        assertEquals("ctx-us", matched.get(0).getWorkerContextId());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-1").stream()
                .filter(item -> "worker-us".equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getWorkerSnapshot().isWorkerLocked());
    }

    @Test
    void recordsRuntimeLockStateForPreLockedWorkers() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("workerContext_status_check", "isWorkerContextAllocatable == true && isWorkerContextAvailable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-locked");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        Worker w = worker("worker-locked", "pool-east");
        workerManager.addWorker(w);
        workerManager.addWorkerContext(w.getWorkerId(), workerContext("worker-locked", "ctx-locked", "shared", "us"));
        assertTrue(workerManager.tryLockWorker(w.getWorkerId()));

        List<MatchedWorkerContext> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-locked").stream()
                .filter(item -> "worker-locked".equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getWorkerSnapshot().isWorkerLocked());
    }

    @Test
    void choosesMatchingContextWhenWorkerHasMultipleContexts() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("workerContext_status_check", "isWorkerContextAllocatable == true && isWorkerContextAvailable == true"),
                rule("workerContext_attribute_country", "workerContextAttributes['country'] == taskRoutingCountryCode")
        ));

        Task task = new Task();
        task.setTid("task-multi");
        task.setProject("demoApp");
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-multi", "pool-east");
        workerManager.addWorker(worker);
        workerManager.addWorkerContext(worker.getWorkerId(), workerContext("worker-multi", "ctx-gb", "shared", "gb"));
        workerManager.addWorkerContext(worker.getWorkerId(), workerContext("worker-multi", "ctx-us", "shared", "us"));

        List<MatchedWorkerContext> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-multi", matched.get(0).getWorkerId());
        assertEquals("ctx-us", matched.get(0).getWorkerContextId());
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private Worker worker(String workerId, String workerGroupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(workerGroupId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        return worker;
    }

    private WorkerContext workerContext(String workerId, String workerContextId, String channel, String country) {
        WorkerContext wc = new WorkerContext();
        wc.setWorkerId(workerId);
        wc.setWorkerContextId(workerContextId);
        wc.setChannel(channel);
        wc.setStatus(WorkerContextStatus.IDLE);
        wc.setAttributes(Map.of("country", country));
        return wc;
    }
}
