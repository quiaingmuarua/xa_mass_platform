package com.xa.mass.engine.strategy;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.runtime.worker.WorkerGroupRecord;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerTaskSelector;
import com.xa.mass.runtime.worker.WorkerCandidateBatch;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.worker.ReserveResult;
import com.xa.mass.runtime.worker.ReserveStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xa.mass.engine.testutil.WorkerRegistrationTestSupport.registerWorker;
import static org.junit.jupiter.api.Assertions.*;

public class RuleBasedTaskWorkerMatchingStrategyTest {

    @Test
    void matchesWorkerUsingWorkerSchedulingAttributesRule() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of("routingCode", "us"), "pool-east", "pool-west"));
        task.setStatus(TaskStatus.READY);

        Worker matchingWorker = worker("worker-us", "pool-east", Map.of("routingTags", "shared,us", "country", "us"));
        Worker nonMatchingWorker = worker("worker-gb", "pool-west", Map.of("routingTags", "shared,gb", "country", "gb"));
        registerWorker(workerManager, matchingWorker);
        registerWorker(workerManager, nonMatchingWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-us", matched.get(0).getWorkerId());
        AssignmentRecord record = recordService.getRecordsByTaskId("task-1").stream()
                .filter(item -> "worker-us".equals(item.getWorkerId()))
                .findFirst()
                .orElseThrow();
        assertTrue(record.getWorkerSnapshot().isWorkerLocked());
        assertEquals(4, record.getRuleEvaluationCount());
        assertTrue(record.getRuleEvaluationTotalTimeMs() >= 0L);
        assertEquals("us", ((Map<?, ?>) record.getContextSnapshot()
                .get("workerSchedulingAttributes")).get("country"));
    }

    @Test
    void emitsAcceptedAndRejectedMatchTraceEvents() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of("routingCode", "us"), "pool-east", "pool-west"));
        task.setStatus(TaskStatus.READY);

        Worker acceptedWorker = worker("worker-us", "pool-east", Map.of("routingTags", "shared,us", "country", "us"));
        Worker rejectedWorker = worker("worker-gb", "pool-west", Map.of("routingTags", "shared,gb", "country", "gb"));
        registerWorker(workerManager, acceptedWorker);
        registerWorker(workerManager, rejectedWorker);

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
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(selector("pool-east"));
        task.setStatus(TaskStatus.READY);

        Worker w = worker("worker-locked", "pool-east");
        registerWorker(workerManager, w);
        assertTrue(workerManager.tryAcquireWorkerExclusiveLease(w.getWorkerId()));

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
                        : WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
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
        task.setSharedConfig(sharedConfig(Map.of("routingCode", "us"), "pool-a", "pool-c", "pool-d"));
        task.setStatus(TaskStatus.READY);

        Worker offlineWorker = worker("worker-offline", "pool-a", Map.of("routingTags", "shared,us", "country", "us"));
        offlineWorker.setStatus(WorkerStatus.OFFLINE);
        registerWorker(workerManager, offlineWorker);

        Worker unsupportedProjectWorker = worker("worker-unsupported", "pool-b",
                Map.of("routingTags", "shared,us", "country", "us"));
        unsupportedProjectWorker.setSupportedProjects(List.of("otherApp"));
        registerWorker(workerManager, unsupportedProjectWorker);

        Worker routingMismatchWorker = worker("worker-routing-mismatch", "pool-c",
                Map.of("routingTags", "shared,gb", "country", "gb"));
        registerWorker(workerManager, routingMismatchWorker);

        Worker acceptedWorker = worker("worker-us", "pool-d", Map.of("routingTags", "shared,us", "country", "us"));
        registerWorker(workerManager, acceptedWorker);

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
                        : WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
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
        task.setSharedConfig(selector("pool-a", "pool-b"));
        task.setStatus(TaskStatus.READY);

        Worker staleWorker = worker("worker-stale", "pool-a");
        registerWorker(workerManager, staleWorker);

        Worker acceptedWorker = worker("worker-online", "pool-b");
        registerWorker(workerManager, acceptedWorker);

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
    void matchesStatelessWorkerWhenTaskHasNoRoutingRequirement() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(selector("pool-east"));
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-stateless", "pool-east");
        registerWorker(workerManager, worker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-stateless", matched.get(0).getWorkerId());
    }

    @Test
    void routingRequirementRejectsStatelessWorkerWithoutSchedulingRoutingTags() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of("routingCode", "us"), "pool-east"));
        task.setStatus(TaskStatus.READY);

        Worker worker = worker("worker-stateless", "pool-east");
        registerWorker(workerManager, worker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertTrue(matched.isEmpty());
        AssignmentRecord record = findRecord(recordService, "task-no-context-routing", "worker-stateless");
        assertEquals("routing code mismatch", record.getReason());
        assertEquals(AssignmentResult.RULE_NOT_MATCH, record.getResult());
        assertEquals(0, record.getRuleEvaluations().size());
    }

    @Test
    void sdkEventTaskMatchesWorkerByIndexedGroupEventCapability() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of("_sdk", Map.of("eventCode", "demo.dispatch")), "pool-east"));
        task.setStatus(TaskStatus.READY);

        Worker eventCapableWorker = worker("worker-event-capable", "pool-east");
        eventCapableWorker.setSupportedProjects(List.of("demoApp"));
        eventCapableWorker.setSupportedEventCodes(List.of("demo.dispatch"));
        registerWorker(workerManager, eventCapableWorker);

        Worker projectOnlyWorker = worker("worker-project-only", "pool-west");
        projectOnlyWorker.setSupportedProjects(List.of("demoApp"));
        projectOnlyWorker.setSupportedEventCodes(List.of("crawler.fetch-page"));
        registerWorker(workerManager, projectOnlyWorker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 2);

        assertEquals(1, matched.size());
        assertEquals("worker-event-capable", matched.get(0).getWorkerId());

        assertNull(findRecordOrNull(recordService, "task-sdk-event", "worker-project-only"));
    }

    @Test
    void sdkEventTaskMatchesWorkerByDeclaredGroupWithoutWorkerLevelCapability() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        workerManager.upsertWorkerGroup(WorkerGroupRecord.builder("group-first")
                .eventBindings(List.of(EventBinding.of("demo.dispatch", List.of("demoApp"))))
                .build());

        Task task = new Task();
        task.setTid("task-group-first-event");
        task.setProject("demoApp");
        task.setSharedConfig(sharedConfig(Map.of(TaskSharedConfig.SDK_METADATA,
                Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch")), "group-first"));
        task.setStatus(TaskStatus.READY);

        Worker worker = new Worker();
        worker.setWorkerId("worker-group-first");
        worker.setWorkerGroupId("group-first");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setLastHeartbeat(LocalDateTime.now());
        registerWorker(workerManager, worker);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-group-first", matched.get(0).getWorkerId());
        AssignmentRecord record = findRecord(recordService, "task-group-first-event", "worker-group-first");
        assertEquals(List.of("demoApp"), record.getWorkerSchedulingSnapshot().getSupportedProjects());
        assertEquals(List.of("demo.dispatch"), record.getWorkerSchedulingSnapshot().getSupportedEventCodes());
    }

    @Test
    void targetWorkerIdPrefilterOnlyMatchesRequestedWorker() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of(TaskSharedConfig.TARGET_WORKER_ID, "worker-b"), "pool-b"));
        task.setStatus(TaskStatus.READY);

        Worker workerA = worker("worker-a", "pool-a");
        Worker workerB = worker("worker-b", "pool-b");
        registerWorker(workerManager, workerA);
        registerWorker(workerManager, workerB);

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
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
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
        task.setSharedConfig(sharedConfig(Map.of(
                TaskSharedConfig.TARGET_WORKER_ATTRIBUTES,
                Map.of("region", "us", "tier", "gold")
        ), "pool-a", "pool-b"));
        task.setStatus(TaskStatus.READY);

        Worker matchingWorker = worker("worker-us-gold", "pool-a");
        matchingWorker.setAttributes(Map.of("region", "us", "tier", "gold"));
        registerWorker(workerManager, matchingWorker);

        Worker nonMatchingWorker = worker("worker-us-silver", "pool-b");
        nonMatchingWorker.setAttributes(Map.of("region", "us", "tier", "silver"));
        registerWorker(workerManager, nonMatchingWorker);

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
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
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
        task.setSharedConfig(selector("pool-a", "pool-b"));
        task.setStatus(TaskStatus.READY);

        Worker highLoadWorker = worker("worker-high-load", "pool-a");
        Worker lowLoadWorker = worker("worker-low-load", "pool-b");
        registerWorker(workerManager, highLoadWorker);
        registerWorker(workerManager, lowLoadWorker);
        workerManager.recordWorkClaimed("worker-high-load", "existing-task-1");
        workerManager.recordWorkClaimed("worker-high-load", "existing-task-2");

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertEquals(1, matched.size());
        assertEquals("worker-low-load", matched.getFirst().getWorkerId());
        assertTrue(workerManager.hasWorkerExclusiveLease("worker-low-load"));
        assertFalse(workerManager.hasWorkerExclusiveLease("worker-high-load"));

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-load-aware", "worker-low-load");
        assertEquals(0, acceptedRecord.getContextSnapshot().get("workerActiveLeaseCount"));
    }

    @Test
    void rejectsRankedCandidateWhenWorkerCapacityCannotBeReserved() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
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
        task.setSharedConfig(selector("pool-a"));
        task.setStatus(TaskStatus.READY);

        registerWorker(workerManager, worker("worker-at-capacity", "pool-a"));
        workerManager.recordWorkClaimed("worker-at-capacity", "existing-task");

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        assertFalse(workerManager.hasWorkerExclusiveLease("worker-at-capacity"));
        assertEquals(0, workerManager.getWorkerLoad("worker-at-capacity").reservedCount());
        AssignmentRecord rejectedRecord = findRecord(recordService, "task-capacity", "worker-at-capacity");
        assertEquals(AssignmentResult.QUOTA_EXCEEDED, rejectedRecord.getResult());
        assertEquals("worker capacity unavailable after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void recordsStructuredReserveFailureAfterRanking() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
        ) {
            @Override
            public ReserveResult reserveWorkerCapacity(String workerId, String taskId) {
                return ReserveResult.rejected(ReserveStatus.STALE_HEARTBEAT, "worker heartbeat stale");
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
        task.setTid("task-stale-reserve");
        task.setProject("demoApp");
        task.setSharedConfig(selector("pool-a"));
        task.setStatus(TaskStatus.READY);

        registerWorker(workerManager, worker("worker-stale-reserve", "pool-a"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        AssignmentRecord rejectedRecord = findRecord(recordService, "task-stale-reserve", "worker-stale-reserve");
        assertEquals(AssignmentResult.RESOURCE_UNAVAILABLE, rejectedRecord.getResult());
        assertEquals("worker reserve rejected after candidate ranking: worker heartbeat stale",
                rejectedRecord.getReason());
    }

    @Test
    void releasesReservationWhenLockConflictHappensAfterRanking() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
        ) {
            @Override
            public boolean tryAcquireWorkerExclusiveLease(String workerId) {
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
        task.setSharedConfig(selector("pool-a"));
        task.setStatus(TaskStatus.READY);

        registerWorker(workerManager, worker("worker-conflict", "pool-a"));

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(task, 1);

        assertTrue(matched.isEmpty());
        assertEquals(0, workerManager.getWorkerLoad("worker-conflict").reservedCount());
        AssignmentRecord rejectedRecord = findRecord(recordService, "task-lock-conflict", "worker-conflict");
        assertEquals(AssignmentResult.CONFLICT, rejectedRecord.getResult());
        assertEquals("worker lock conflict after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void backgroundTaskReservesCapacityWithoutExclusiveWorkerLock() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
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
        registerWorker(workerManager, worker);

        Task first = backgroundTask("task-background-1");
        Task second = backgroundTask("task-background-2");
        Task third = backgroundTask("task-background-3");

        List<WorkerSchedulingCandidate> firstMatch = strategy.matchWorkers(first, 1);
        List<WorkerSchedulingCandidate> secondMatch = strategy.matchWorkers(second, 1);
        List<WorkerSchedulingCandidate> thirdMatch = strategy.matchWorkers(third, 1);

        assertEquals(1, firstMatch.size());
        assertEquals(1, secondMatch.size());
        assertTrue(thirdMatch.isEmpty());
        assertFalse(workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertEquals(2, workerManager.getWorkerLoad("worker-shared").reservedCount());

        AssignmentRecord acceptedRecord = findRecord(recordService, "task-background-1", "worker-shared");
        assertEquals(AssignmentResult.SUCCESS, acceptedRecord.getResult());
        assertFalse(acceptedRecord.getWorkerSnapshot().isWorkerLocked());
        assertEquals("all rules matched and worker capacity reserved after candidate ranking", acceptedRecord.getReason());

        AssignmentRecord rejectedRecord = findRecord(recordService, "task-background-3", "worker-shared");
        assertEquals(AssignmentResult.QUOTA_EXCEEDED, rejectedRecord.getResult());
        assertEquals("worker capacity unavailable after candidate ranking", rejectedRecord.getReason());
    }

    @Test
    void assignmentContextRecordsWarmCandidateSourceStatsFromCandidateBatch() {
        WorkerManager workerManager = new WorkerManager(
                new InMemoryWorkerStorage(),
                workerId -> WorkerReachabilityState.ONLINE,
                new InMemoryWorkerRegistry()
        );
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        ruleManager.addDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("app_support_check", "supportsProject == true")
        ));

        Worker worker = worker("worker-warm-diagnostics", "pool-a");
        worker.setMaxConcurrentWork(2);
        registerWorker(workerManager, worker);

        Task task = backgroundTask("task-warm-diagnostics");
        workerManager.recordWarmCandidate(task, worker);
        assertEquals(1, strategy.matchWorkers(task, 1).size());

        assertTrue(recordService.getRecordsByTaskId("task-warm-diagnostics").stream()
                .filter(record -> "worker-warm-diagnostics".equals(record.getWorkerId()))
                .anyMatch(record -> Integer.valueOf(1).equals(record.getContextSnapshot()
                        .get("workerCandidateWarmCount"))
                        && Integer.valueOf(1).equals(record.getContextSnapshot()
                        .get("workerCandidateColdCount"))
                        && Integer.valueOf(0).equals(record.getContextSnapshot()
                        .get("workerCandidateWarmRejectedCount"))));
    }

    @Test
    void matchWorkersOversamplesStageOneCandidateAcquisitionBeforeDispatchLimit() {
        Worker worker = worker("worker-sampled", "pool-a");
        RecordingWorkerManager workerManager = new RecordingWorkerManager(List.of(worker));
        RuleManager<Map<String, Object>> ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        AssignmentRecordService recordService = new AssignmentRecordService();
        RuleBasedTaskWorkerMatchingStrategy strategy =
                new RuleBasedTaskWorkerMatchingStrategy(ruleManager, workerManager, recordService);

        List<WorkerSchedulingCandidate> matched = strategy.matchWorkers(backgroundTask("task-sampled"), 1);

        assertEquals(1, matched.size());
        assertTrue(workerManager.lastMaxCandidateCount > 1);
        assertTrue(workerManager.lastMaxCandidateCount >= RuleBasedTaskWorkerMatchingStrategy.DEFAULT_STAGE_ONE_SAMPLE_MIN);
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
        worker.setLastHeartbeat(LocalDateTime.now());
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        worker.setAttributes(attributes);
        return worker;
    }

    private Task backgroundTask(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        task.setProject("demoApp");
        task.setSharedConfig(selector("pool-a"));
        task.setStatus(TaskStatus.READY);
        task.getExecutionSpec().setForeground(false);
        return task;
    }

    private static Map<String, Object> selector(String... groupIds) {
        return sharedConfig(Map.of(), groupIds);
    }

    private static Map<String, Object> sharedConfig(Map<String, Object> base, String... groupIds) {
        java.util.LinkedHashMap<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        if (base != null) {
            sharedConfig.putAll(base);
        }
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_IDS, List.of(groupIds));
        return Map.copyOf(sharedConfig);
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

    private static final class RecordingWorkerManager extends WorkerManager {
        private final List<Worker> candidates;
        private int lastMaxCandidateCount;

        private RecordingWorkerManager(List<Worker> candidates) {
            super(new InMemoryWorkerStorage(),
                    workerId -> WorkerReachabilityState.ONLINE,
                    new InMemoryWorkerRegistry());
            this.candidates = List.copyOf(candidates);
        }

        @Override
        public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                                 int maxCandidateCount) {
            lastMaxCandidateCount = maxCandidateCount;
            return new WorkerCandidateBatch<>(candidates.stream()
                    .map(TestWorkerCandidateRows::from)
                    .toList(), 0, candidates.size(), 0);
        }

        @Override
        public boolean tryReserveWorkerCapacity(String workerId, String taskId) {
            return true;
        }

        @Override
        public ReserveResult reserveWorkerCapacity(String workerId, String taskId) {
            return ReserveResult.accepted(null);
        }

        @Override
        public boolean isWorkerDispatchEnabled(String workerId) {
            return true;
        }
    }
}
