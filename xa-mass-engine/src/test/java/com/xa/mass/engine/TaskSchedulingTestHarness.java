package com.xa.mass.engine;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.rules.RegistryBackedMatchingRuleEvaluator;
import com.xa.mass.engine.rules.RuleEvaluatorRegistries;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategy;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityView;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskSchedulingTestHarness {

    private static final String DEFAULT_ADAPTER_NODE_ID = "test-node-main";
    private static final String DEFAULT_WORKER_GROUP_ID = "pool-main";

    final InMemoryTaskShellRuntimeStore taskStorage;
    final TaskManager taskManager;
    final WorkerManager workerManager;
    final RuleStorage ruleStorage;
    final AssignmentRecordService assignmentRecords;
    final List<TaskDispatchBinding> dispatches;
    final TaskWorkerAssignListener assignListener;

    TaskSchedulingTestHarness() {
        this(WorkerReachabilityView.permissive());
    }

    TaskSchedulingTestHarness(WorkerReachabilityView reachabilityView) {
        this.taskStorage = new InMemoryTaskShellRuntimeStore();
        this.taskManager = new TaskManager(
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
        this.workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), reachabilityView, new InMemoryWorkerRegistry());
        this.ruleStorage = new InMemoryRuleDefinitionStore();
        this.assignmentRecords = new AssignmentRecordService();
        this.dispatches = new ArrayList<>();
        installDefaultSchedulingRules();
        installDefaultWorkerRegistrationSpine();

        SimpleTaskDispatchBinder binder = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                assignmentRecords,
                (context, bindings) -> dispatches.addAll(bindings)
        );
        RuleBasedTaskWorkerMatchingStrategy matchingStrategy = new RuleBasedTaskWorkerMatchingStrategy(
                () -> ruleStorage.getAllRules().stream()
                        .filter(RuleDefinition::isEnabled)
                        .sorted(Comparator.comparingInt(RuleDefinition::getPriority))
                        .toList(),
                new RegistryBackedMatchingRuleEvaluator(RuleEvaluatorRegistries.defaultRegistry()),
                workerManager,
                workerManager,
                workerManager,
                assignmentRecords,
                com.xa.mass.engine.TraceEventLogger.noop()
        );
        this.assignListener = new TaskWorkerAssignListener(
                matchingStrategy,
                workerManager,
                workerManager,
                binder,
                taskManager,
                taskManager.events()
        );

        TaskResourceReleaseListener releaseListener = new TaskResourceReleaseListener(
                taskManager,
                taskManager,
                workerManager);
        taskManager.events().addTaskWorkAttemptClosedListener(releaseListener::onTaskWorkAttemptClosed);
        taskManager.events().addTaskTerminalListener(releaseListener::onTaskTerminal);
    }

    Task createReadyBatchTask(String sourceRef, List<Map<String, Object>> items) {
        Task task = createBatchTask(sourceRef, items, 0, 1);
        assertTrue(taskManager.approveTask(task.getTid()));
        return taskManager.getTask(task.getTid());
    }

    Task createSessionTask(String sourceRef,
                           List<Map<String, Object>> items,
                           int defaultMaxRetryCount,
                           int batchSize) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setUserId("agent");
        request.setProject("demoApp");
        request.setContract(TaskContract.SESSION);
        request.setSourceRef(sourceRef);
        request.setSharedConfig(defaultSharedConfig(Map.of(TaskSharedConfig.ROUTING_CODE, "us")));
        TaskExecutionSpec executionSpec = new TaskExecutionSpec();
        executionSpec.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
        executionSpec.setBatchSize(batchSize);
        executionSpec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        request.setExecutionSpec(executionSpec);

        Task task = taskManager.createTaskShell(request);
        if (items != null && !items.isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), items);
        }
        return taskManager.getTask(task.getTid());
    }

    Task createBatchTask(String sourceRef,
                         List<Map<String, Object>> items,
                         int defaultMaxRetryCount,
                         int batchSize) {
        return createBatchTask(
                sourceRef,
                items,
                defaultMaxRetryCount,
                batchSize,
                Map.of(TaskSharedConfig.ROUTING_CODE, "us"),
                0
        );
    }

    Task createBatchTask(String sourceRef,
                         List<Map<String, Object>> items,
                         int defaultMaxRetryCount,
                         int batchSize,
                         Map<String, Object> sharedConfig,
                         int minRequiredWorkerCount) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setUserId("agent");
        request.setProject("demoApp");
        request.setContract(TaskContract.BATCH);
        request.setSourceRef(sourceRef);
        request.setSharedConfig(defaultSharedConfig(sharedConfig));
        TaskExecutionSpec executionSpec = new TaskExecutionSpec();
        executionSpec.setWorkloadClass(TaskWorkloadClass.BULK);
        executionSpec.setBatchSize(batchSize);
        executionSpec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        request.setExecutionSpec(executionSpec);

        Task task = taskManager.createTaskShell(request);
        if (minRequiredWorkerCount > 0) {
            task.setMinRequiredWorkerCount(minRequiredWorkerCount);
            assertTrue(taskManager.updateTask(task));
        }
        if (items != null && !items.isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), items);
        }
        assertTrue(taskManager.sealTask(task.getTid()));
        return taskManager.getTask(task.getTid());
    }

    Worker addWorker(String workerId, String routingCode) {
        return addWorker(workerId, routingCode, Map.of());
    }

    Worker addStatelessWorker(String workerId, int maxConcurrentWork) {
        Worker worker = worker(workerId);
        worker.setMaxConcurrentWork(maxConcurrentWork);
        workerManager.addWorker(workerResource(worker));
        return worker;
    }

    Worker addWorker(String workerId,
                     String routingCode,
                     Map<String, String> attributes) {
        Worker worker = worker(workerId);
        worker.setAttributes(workerAttributes(routingCode, attributes));
        workerManager.addWorker(workerResource(worker));
        return worker;
    }

    private static WorkerResourceRecord workerResource(Worker worker) {
        return new WorkerResourceRecord(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

    TaskWorkStats stats(String taskId) {
        return taskManager.getTaskWorkRuntime().stats(taskId);
    }

    List<ActiveLeaseRecord> activeLeases(String taskId) {
        return taskManager.getTaskWorkRuntime().activeLeases(taskId);
    }

    AssignmentRecord record(String taskId, String workerId) {
        return assignmentRecords.getRecordsByTaskId(taskId).stream()
                .filter(record -> workerId.equals(record.getWorkerId()))
                .filter(record -> record.getMessageId() == null)
                .findFirst()
                .orElseThrow();
    }

    List<AssignmentRecord> workerRecords(String taskId, String workerId) {
        return assignmentRecords.getRecordsByTaskId(taskId).stream()
                .filter(record -> workerId.equals(record.getWorkerId()))
                .filter(record -> record.getMessageId() == null)
                .toList();
    }

    long successfulMessageAssignments(String taskId, String workerId) {
        return assignmentRecords.getRecordsByTaskId(taskId).stream()
                .filter(record -> workerId.equals(record.getWorkerId()))
                .filter(record -> record.getMessageId() != null)
                .count();
    }

    Map<String, Object> item(String target) {
        return Map.of("target", target);
    }

    private void installDefaultSchedulingRules() {
        ruleStorage.addRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("routing_code_match", "taskHasRoutingRequirement == false || workerSchedulingMatchesRoutingCode == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("target_worker_attributes_check", "matchesTargetWorkerAttributes == true")
        ));
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setLastHeartbeat(LocalDateTime.now());
        worker.setAdapterNodeId(DEFAULT_ADAPTER_NODE_ID);
        worker.setWorkerGroupId(DEFAULT_WORKER_GROUP_ID);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of());
        return worker;
    }

    private Map<String, String> workerAttributes(String routingCode, Map<String, String> attributes) {
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (routingCode != null && !routingCode.isBlank()) {
            merged.put("routingTags", "shared," + routingCode);
            merged.put("country", routingCode);
        }
        if (attributes != null) {
            merged.putAll(attributes);
        }
        return Map.copyOf(merged);
    }

    private Map<String, Object> defaultSharedConfig(Map<String, Object> sharedConfig) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (sharedConfig != null) {
            merged.putAll(sharedConfig);
        }
        merged.putIfAbsent(TaskSharedConfig.WORKER_GROUP_ID, DEFAULT_WORKER_GROUP_ID);
        return Map.copyOf(merged);
    }

    private void installDefaultWorkerRegistrationSpine() {
        workerManager.upsertWorkerGroup(WorkerGroupRecord.builder(DEFAULT_WORKER_GROUP_ID)
                .projectCodes(List.of("demoApp"))
                .build());
        workerManager.registerAdapterNode(new AdapterNodeRecord(
                DEFAULT_ADAPTER_NODE_ID,
                "test",
                "1.0.0",
                "endpoint-" + DEFAULT_ADAPTER_NODE_ID,
                true,
                true,
                null,
                null,
                Map.of()
        ));
        workerManager.bindNodeGroup(new NodeGroupBindingRecord(
                DEFAULT_ADAPTER_NODE_ID,
                DEFAULT_WORKER_GROUP_ID,
                "test-plugin",
                "test-deployment",
                true,
                false,
                null,
                null,
                Map.of()
        ));
    }

}
