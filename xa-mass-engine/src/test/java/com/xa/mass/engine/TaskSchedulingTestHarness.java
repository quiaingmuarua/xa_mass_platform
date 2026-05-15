package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskSchedulingTestHarness {

    final InMemoryTaskStorage taskStorage;
    final TaskManager taskManager;
    final WorkerManager workerManager;
    final RuleManager<Map<String, Object>> ruleManager;
    final AssignmentRecordService assignmentRecords;
    final List<TaskDispatchBinding> dispatches;
    final TaskWorkerAssignListener assignListener;

    TaskSchedulingTestHarness() {
        this(WorkerReachabilityView.permissive());
    }

    TaskSchedulingTestHarness(WorkerReachabilityView reachabilityView) {
        this.taskStorage = new InMemoryTaskStorage();
        this.taskManager = new TaskManager(
                taskStorage,
                taskStorage,
                new InMemoryTaskWorkRuntime()
        );
        this.workerManager = new WorkerManager(new InMemoryWorkerStorage(), reachabilityView);
        this.ruleManager = new RuleManager<>(new InMemoryRuleStorage());
        this.assignmentRecords = new AssignmentRecordService();
        this.dispatches = new ArrayList<>();
        installDefaultSchedulingRules();

        SimpleTaskDispatchBinder binder = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                assignmentRecords,
                (context, bindings) -> dispatches.addAll(bindings)
        );
        this.assignListener = new TaskWorkerAssignListener(
                ruleManager,
                workerManager,
                binder,
                assignmentRecords,
                taskManager,
                taskManager.events()
        );

        TaskResourceReleaseListener releaseListener = new TaskResourceReleaseListener(taskManager, workerManager);
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
        request.setSharedConfig(Map.of(TaskSharedConfig.ROUTING_CODE, "us"));
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
        request.setSharedConfig(sharedConfig == null ? Map.of() : sharedConfig);
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

    Worker addWorkerWithContext(String workerId, String contextId, String routingCode) {
        return addWorkerWithContext(workerId, contextId, routingCode, Map.of());
    }

    Worker addWorkerWithContext(String workerId,
                                String contextId,
                                String routingCode,
                                Map<String, String> attributes) {
        Worker worker = worker(workerId);
        worker.setAttributes(attributes);
        workerManager.addWorker(worker);
        workerManager.addWorkerContext(workerContext(workerId, contextId, routingCode, WorkerContextStatus.IDLE));
        return worker;
    }

    Worker addWorkerWithContext(String workerId,
                                String contextId,
                                String routingCode,
                                WorkerContextStatus status) {
        Worker worker = worker(workerId);
        workerManager.addWorker(worker);
        WorkerContext context = workerContext(workerId, contextId, routingCode, status);
        if (status == WorkerContextStatus.OCCUPIED || status == WorkerContextStatus.RESERVED) {
            context.setLastBindTaskId("other-task");
        }
        workerManager.addWorkerContext(context);
        return worker;
    }

    void addContextToWorker(String workerId, String contextId, String routingCode) {
        workerManager.addWorkerContext(workerContext(workerId, contextId, routingCode, WorkerContextStatus.IDLE));
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
        ruleManager.addDefaultRules(List.of(
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
        worker.setWorkerGroupId("pool-main");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of());
        return worker;
    }

    private WorkerContext workerContext(String workerId,
                                        String workerContextId,
                                        String routingCode,
                                        WorkerContextStatus status) {
        WorkerContext context = new WorkerContext();
        context.setWorkerId(workerId);
        context.setWorkerContextId(workerContextId);
        context.setStatus(status);
        context.setRoutingTags(Set.of("shared", routingCode));
        context.setAttributes(Map.of("country", routingCode));
        return context;
    }

}
