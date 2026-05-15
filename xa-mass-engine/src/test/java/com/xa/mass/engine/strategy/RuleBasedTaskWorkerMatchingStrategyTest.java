package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.load.InMemoryWorkerLoadView;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RuleBasedTaskWorkerMatchingStrategyTest {

    @Test
    void matchesWorkerUsingWorkerSchedulingAttributesRule() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("worker_scheduling_attribute_country", "workerSchedulingAttributes['country'] == routingCode")
        ));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        Worker matchingWorker = worker("worker-us", "pool-east", Map.of("routingTags", "shared,us", "country", "us"));
        Worker nonMatchingWorker = worker("worker-gb", "pool-west", Map.of("routingTags", "shared,gb", "country", "gb"));
        workerManager.addWorker(matchingWorker);
        workerManager.addWorker(nonMatchingWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-us", matched.get(0).getWorkerId());
        assertNull(matched.get(0).getWorkerContextId());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-1").stream()
                .filter(item -> "worker-us".equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getWorkerSnapshot().isWorkerLocked());
        assertEquals("us", ((Map<?, ?>) record.getContextSnapshot()
                .get("workerSchedulingAttributes")).get("country"));
    }

    @Test
    void emitsAcceptedAndRejectedMatchTraceEvents() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("worker_scheduling_attribute_country", "workerSchedulingAttributes['country'] == routingCode")
        ));

        Task task = new Task();
        task.setTid("task-trace");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        Worker acceptedWorker = worker("worker-us", "pool-east", Map.of("routingTags", "shared,us", "country", "us"));
        Worker rejectedWorker = worker("worker-gb", "pool-west", Map.of("routingTags", "shared,gb", "country", "gb"));
        workerManager.addWorker(acceptedWorker);
        workerManager.addWorker(rejectedWorker);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

            assertEquals(1, matched.size());
            capture.assertHasEvent("WORKER_MATCH_ACCEPTED", mdc ->
                    "task-trace".equals(mdc.get("taskId"))
                            && "worker-us".equals(mdc.get("workerId"))
                            && mdc.get("workerContextId") == null
                            && "1".equals(mdc.get("candidateRank"))
                            && "1".equals(mdc.get("workerReservedCount"))
                            && mdc.get("workerEstimatedLoadRatio") != null);
            capture.assertHasEvent("WORKER_MATCH_REJECTED", mdc ->
                    "task-trace".equals(mdc.get("taskId"))
                            && "worker-gb".equals(mdc.get("workerId"))
                            && mdc.get("workerContextId") == null
                            && "routing code mismatch".equals(mdc.get("reason")));
        }
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
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-locked");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        Worker w = worker("worker-locked", "pool-east");
        workerManager.addWorker(w);
        assertTrue(workerManager.tryLockWorker(w.getWorkerId()));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-locked").stream()
                .filter(item -> "worker-locked".equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getWorkerSnapshot().isWorkerLocked());
        assertEquals("worker locked", record.getReason());
        assertEquals(0, record.getRuleEvaluations().size());
    }

    @Test
    void prefilterRejectsOfflineUnsupportedAndRoutingMismatchCandidatesBeforeRuleEvaluation() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> "worker-offline".equals(workerId)
                        ? WorkerReachabilityState.OFFLINE
                        : WorkerReachabilityState.ONLINE
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("routing_code_match", "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-prefilter");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        Worker offlineWorker = worker("worker-offline", "pool-a", Map.of("routingTags", "shared,us", "country", "us"));
        offlineWorker.setStatus(WorkerStatus.OFFLINE);
        workerManager.addWorker(offlineWorker);

        Worker unsupportedProjectWorker = worker("worker-unsupported", "pool-b",
                Map.of("routingTags", "shared,us", "country", "us"));
        unsupportedProjectWorker.setSupportedProjects(List.of("otherApp"));
        workerManager.addWorker(unsupportedProjectWorker);

        Worker routingMismatchWorker = worker("worker-routing-mismatch", "pool-c",
                Map.of("routingTags", "shared,gb", "country", "gb"));
        workerManager.addWorker(routingMismatchWorker);

        Worker acceptedWorker = worker("worker-us", "pool-d", Map.of("routingTags", "shared,us", "country", "us"));
        workerManager.addWorker(acceptedWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-us", matched.get(0).getWorkerId());

        AssignmentRecord offlineRecord = findRecord(recordService, "task-prefilter", "worker-offline");
        assertEquals("worker transport unreachable", offlineRecord.getReason());
        assertEquals(AssignmentResult.RESOURCE_UNAVAILABLE, offlineRecord.getResult());
        assertEquals(0, offlineRecord.getRuleEvaluations().size());

        assertNull(findRecordOrNull(recordService, "task-prefilter", "worker-unsupported"));

        AssignmentRecord routingMismatchRecord = findRecord(recordService, "task-prefilter", "worker-routing-mismatch");
        assertEquals("routing code mismatch", routingMismatchRecord.getReason());
        assertEquals(AssignmentResult.RULE_NOT_MATCH, routingMismatchRecord.getResult());
        assertEquals(0, routingMismatchRecord.getRuleEvaluations().size());
        assertEquals("worker-routing-mismatch",
                routingMismatchRecord.getContextSnapshot().get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "gb"),
                routingMismatchRecord.getContextSnapshot().get("workerSchedulingRoutingTags"));
        assertEquals("gb",
                ((Map<?, ?>) routingMismatchRecord.getContextSnapshot()
                        .get("workerSchedulingAttributes")).get("country"));
        assertEquals(Boolean.FALSE,
                routingMismatchRecord.getContextSnapshot().get("workerSchedulingMatchesRoutingCode"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-prefilter", "worker-us");
        assertFalse(acceptedRecord.getRuleEvaluations().isEmpty());
    }

    @Test
    void prefilterRejectsStaleTransportPresenceBeforeRuleEvaluation() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> "worker-stale".equals(workerId)
                        ? WorkerReachabilityState.STALE
                        : WorkerReachabilityState.ONLINE
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-stale-prefilter");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        Worker staleWorker = worker("worker-stale", "pool-a");
        workerManager.addWorker(staleWorker);

        Worker acceptedWorker = worker("worker-online", "pool-b");
        workerManager.addWorker(acceptedWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-online", matched.get(0).getWorkerId());

        AssignmentRecord staleRecord = findRecord(recordService, "task-stale-prefilter", "worker-stale");
        assertEquals("worker transport unreachable", staleRecord.getReason());
        assertEquals(AssignmentResult.RESOURCE_UNAVAILABLE, staleRecord.getResult());
        assertEquals("STALE", staleRecord.getContextSnapshot().get("transportReachability"));
        assertEquals(Boolean.FALSE, staleRecord.getContextSnapshot().get("isTransportReachable"));
        assertEquals(0, staleRecord.getRuleEvaluations().size());
    }

    @Test
    void legacyContextMatchingChoosesMatchingContextWhenWorkerHasMultipleContexts() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("worker_scheduling_attribute_country", "workerSchedulingAttributes['country'] == routingCode")
        ));

        Task task = new Task();
        task.setTid("task-multi");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-multi", "pool-east");
        workerManager.addWorker(worker);
        workerManager.addWorkerContext(workerContext("worker-multi", "ctx-gb", "shared", "gb"));
        workerManager.addWorkerContext(workerContext("worker-multi", "ctx-us", "shared", "us"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-multi", matched.get(0).getWorkerId());
        assertEquals("ctx-us", matched.get(0).getWorkerContextId());
    }

    @Test
    void matchesStatelessWorkerWhenTaskHasNoRoutingRequirement() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("routing_code_match", "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-no-context");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of());
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-stateless", "pool-east");
        workerManager.addWorker(worker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-stateless", matched.get(0).getWorkerId());
        assertEquals(null, matched.get(0).getWorkerContextId());
    }

    @Test
    void routingRequirementRejectsStatelessWorkerWithoutSchedulingRoutingTags() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("routing_code_match", "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-no-context-routing");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-stateless", "pool-east");
        workerManager.addWorker(worker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = findRecord(recordService, "task-no-context-routing", "worker-stateless");
        assertEquals("routing code mismatch", record.getReason());
        assertEquals(AssignmentResult.RULE_NOT_MATCH, record.getResult());
        assertEquals(0, record.getRuleEvaluations().size());
    }

    @Test
    void legacyContextProjectMismatchIsRejectedBeforeRuleEvaluation() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-context-project-mismatch");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-mismatch", "pool-east");
        workerManager.addWorker(worker);
        WorkerContext workerContext = workerContext("worker-mismatch", "ctx-mismatch", "shared", "us");
        workerContext.setProject("testApp");
        workerManager.addWorkerContext(workerContext);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = findRecord(recordService, "task-context-project-mismatch", "worker-mismatch");
        assertEquals("workerContext project mismatch", record.getReason());
        assertEquals(AssignmentResult.RULE_NOT_MATCH, record.getResult());
        assertEquals(0, record.getRuleEvaluations().size());
        assertNotNull(record.getContextSnapshot());
        assertEquals(Boolean.FALSE,
                record.getContextSnapshot().get("workerSchedulingProjectMatchesTaskProject"));
    }

    @Test
    void sdkEventTaskMatchesWorkerByExplicitEventCapabilityWithoutProjectHint() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_capability_check",
                        "((taskEventCode == null || taskEventCode == '') && supportsProject == true) "
                                + "|| ((taskEventCode != null && taskEventCode != '') && supportsEvent == true)")
        ));

        Task task = new Task();
        task.setTid("task-sdk-event");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("_sdk", Map.of("eventCode", "demo.dispatch")));
        task.setStatus(TaskStatus.READY);

        Worker eventCapableWorker = worker("worker-event-capable", "pool-east");
        eventCapableWorker.setSupportedProjects(List.of());
        eventCapableWorker.setSupportedEventCodes(List.of("demo.dispatch"));
        workerManager.addWorker(eventCapableWorker);

        Worker projectOnlyWorker = worker("worker-project-only", "pool-west");
        projectOnlyWorker.setSupportedProjects(List.of("demoApp"));
        projectOnlyWorker.setSupportedEventCodes(List.of("crawler.fetch-page"));
        workerManager.addWorker(projectOnlyWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-event-capable", matched.get(0).getWorkerId());

        assertNull(findRecordOrNull(recordService, "task-sdk-event", "worker-project-only"));
    }

    @Test
    void targetWorkerIdPrefilterOnlyMatchesRequestedWorker() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true"),
                rule("target_worker_check", "matchesTargetWorkerId == true")
        ));

        Task task = new Task();
        task.setTid("task-target-worker");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of(TaskSharedConfig.TARGET_WORKER_ID, "worker-b"));
        task.setStatus(TaskStatus.READY);

        Worker workerA = worker("worker-a", "pool-a");
        Worker workerB = worker("worker-b", "pool-b");
        workerManager.addWorker(workerA);
        workerManager.addWorker(workerB);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-b", matched.get(0).getWorkerId());

        assertNull(findRecordOrNull(recordService, "task-target-worker", "worker-a"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-target-worker", "worker-b");
        assertFalse(acceptedRecord.getRuleEvaluations().isEmpty());
        assertEquals(Boolean.TRUE, acceptedRecord.getContextSnapshot().get("matchesTargetWorkerId"));
    }

    @Test
    void targetWorkerAttributesPrefilterOnlyMatchesWorkersWithRequestedAttributes() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true"),
                rule("target_worker_attributes_check", "matchesTargetWorkerAttributes == true")
        ));

        Task task = new Task();
        task.setTid("task-target-attrs");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of(
                TaskSharedConfig.TARGET_WORKER_ATTRIBUTES,
                Map.of("region", "us", "tier", "gold")
        ));
        task.setStatus(TaskStatus.READY);

        Worker matchingWorker = worker("worker-us-gold", "pool-a");
        matchingWorker.setAttributes(Map.of("region", "us", "tier", "gold"));
        workerManager.addWorker(matchingWorker);

        Worker nonMatchingWorker = worker("worker-us-silver", "pool-b");
        nonMatchingWorker.setAttributes(Map.of("region", "us", "tier", "silver"));
        workerManager.addWorker(nonMatchingWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-us-gold", matched.get(0).getWorkerId());

        AssignmentRecord rejectedRecord = findRecord(recordService, "task-target-attrs", "worker-us-silver");
        assertEquals("target worker attributes mismatch", rejectedRecord.getReason());
        assertEquals(AssignmentResult.RULE_NOT_MATCH, rejectedRecord.getResult());
        assertEquals(0, rejectedRecord.getRuleEvaluations().size());
        assertEquals(Boolean.FALSE, rejectedRecord.getContextSnapshot().get("matchesTargetWorkerAttributes"));
        assertEquals(Map.of("region", "us", "tier", "gold"),
                rejectedRecord.getContextSnapshot().get("taskTargetWorkerAttributes"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-target-attrs", "worker-us-gold");
        assertFalse(acceptedRecord.getRuleEvaluations().isEmpty());
        assertEquals(Boolean.TRUE, acceptedRecord.getContextSnapshot().get("matchesTargetWorkerAttributes"));
    }

    @Test
    void ranksRulePassedCandidatesByObservedLoadBeforeLockAcquisition() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        loadView.recordWorkClaimed("worker-high-load", "existing-task-1");
        loadView.recordWorkClaimed("worker-high-load", "existing-task-2");
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                loadView
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-load-aware");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        Worker highLoadWorker = worker("worker-high-load", "pool-a");
        Worker lowLoadWorker = worker("worker-low-load", "pool-b");
        workerManager.addWorker(highLoadWorker);
        workerManager.addWorker(lowLoadWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-low-load", matched.getFirst().getWorkerId());
        assertTrue(workerManager.isLocked("worker-low-load"));
        assertFalse(workerManager.isLocked("worker-high-load"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-load-aware", "worker-low-load");
        assertEquals(0, acceptedRecord.getContextSnapshot().get("workerActiveLeaseCount"));
    }

    @Test
    void rejectsRankedCandidateWhenWorkerCapacityCannotBeReserved() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        loadView.recordWorkClaimed("worker-at-capacity", "existing-task");
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                loadView
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-capacity");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        workerManager.addWorker(worker("worker-at-capacity", "pool-a"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        assertFalse(workerManager.isLocked("worker-at-capacity"));
        assertEquals(0, loadView.getReservedCount("worker-at-capacity"));
        AssignmentRecord rejectedRecord = findRecord(recordService, "task-capacity", "worker-at-capacity");
        assertEquals(AssignmentResult.QUOTA_EXCEEDED, rejectedRecord.getResult());
        assertEquals("worker capacity unavailable after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void releasesReservationWhenLockConflictHappensAfterRanking() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                loadView
        ) {
            @Override
            public boolean tryLockWorker(String workerId) {
                return false;
            }
        };
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Task task = new Task();
        task.setTid("task-lock-conflict");
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);

        workerManager.addWorker(worker("worker-conflict", "pool-a"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        assertEquals(0, loadView.getReservedCount("worker-conflict"));
        AssignmentRecord rejectedRecord = findRecord(recordService, "task-lock-conflict", "worker-conflict");
        assertEquals(AssignmentResult.CONFLICT, rejectedRecord.getResult());
        assertEquals("worker lock conflict after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void backgroundTaskReservesCapacityWithoutExclusiveWorkerLock() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                loadView
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Worker worker = worker("worker-shared", "pool-a");
        worker.setMaxConcurrentWork(2);
        workerManager.addWorker(worker);

        Task first = backgroundTask("task-background-1");
        Task second = backgroundTask("task-background-2");
        Task third = backgroundTask("task-background-3");

        List<WorkerSchedulingCandidate> firstMatch = strategy.matchWorkers(first, 1);
        List<WorkerSchedulingCandidate> secondMatch = strategy.matchWorkers(second, 1);
        List<WorkerSchedulingCandidate> thirdMatch = strategy.matchWorkers(third, 1);

        assertEquals(1, firstMatch.size());
        assertEquals(1, secondMatch.size());
        assertTrue(thirdMatch.isEmpty());
        assertFalse(workerManager.isLocked("worker-shared"));
        assertEquals(2, loadView.getReservedCount("worker-shared"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-background-1", "worker-shared");
        assertEquals(AssignmentResult.SUCCESS, acceptedRecord.getResult());
        assertFalse(acceptedRecord.getWorkerSnapshot().isWorkerLocked());
        assertEquals("all rules matched and worker capacity reserved after candidate ranking", acceptedRecord.getReason());

        AssignmentRecord rejectedRecord = findRecord(recordService, "task-background-3", "worker-shared");
        assertEquals(AssignmentResult.QUOTA_EXCEEDED, rejectedRecord.getResult());
        assertEquals("worker capacity unavailable after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void legacyContextBackedBackgroundTaskStillAcquiresExclusiveWorkerLock() {
        InMemoryWorkerLoadView loadView = new InMemoryWorkerLoadView();
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                loadView
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        Worker worker = worker("worker-context-backed", "pool-a");
        worker.setMaxConcurrentWork(2);
        workerManager.addWorker(worker);
        workerManager.addWorkerContext(workerContext("worker-context-backed", "ctx-1", "shared", "us"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(backgroundTask("task-background-context"), 1);

        assertEquals(1, matched.size());
        assertEquals("ctx-1", matched.get(0).getWorkerContextId());
        assertTrue(workerManager.isLocked("worker-context-backed"));
        assertEquals(1, loadView.getReservedCount("worker-context-backed"));

        AssignmentRecord acceptedRecord = findRecord(recordService,
                "task-background-context", "worker-context-backed");
        assertEquals(AssignmentResult.SUCCESS, acceptedRecord.getResult());
        assertTrue(acceptedRecord.getWorkerSnapshot().isWorkerLocked());
        assertEquals("all rules matched and worker lock acquired after candidate ranking",
                acceptedRecord.getReason());
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private Worker worker(String workerId, String workerGroupId) {
        return worker(workerId, workerGroupId, Map.of());
    }

    private Worker worker(String workerId, String workerGroupId, Map<String, String> attributes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(workerGroupId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        worker.setAttributes(attributes);
        return worker;
    }

    private WorkerContext workerContext(String workerId, String workerContextId, String routingTag, String country) {
        WorkerContext wc = new WorkerContext();
        wc.setWorkerId(workerId);
        wc.setWorkerContextId(workerContextId);
        wc.setRoutingTags(java.util.Set.of(routingTag, country));
        wc.setStatus(WorkerContextStatus.IDLE);
        wc.setAttributes(Map.of("country", country));
        return wc;
    }

    private Task backgroundTask(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);
        task.getExecutionSpec().setForeground(false);
        return task;
    }

    private AssignmentRecord findRecord(AssignmentRecordService recordService, String taskId, String workerId) {
        return recordService.getRecordsByTaskId(taskId).stream()
                .filter(item -> workerId.equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
    }

    private AssignmentRecord findRecordOrNull(AssignmentRecordService recordService, String taskId, String workerId) {
        return recordService.getRecordsByTaskId(taskId).stream()
                .filter(item -> workerId.equals(item.getWorkerId()))
                .findFirst()
                .orElse(null);
    }
}
