package com.xa.mass.engine;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.base.enums.assignment.AssignmentType;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.control.WorkerControlService;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.engine.testutil.WorkerTestFixture;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionResult;
import com.xa.mass.worker.runtime.report.WorkerStateReport;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskSchedulingTestHarness {

    private static final String DEFAULT_ADAPTER_NODE_ID = "test-node-main";
    private static final String DEFAULT_WORKER_GROUP_ID = "pool-main";

    final InMemoryTaskShellRuntimeStore taskStorage;
    final TaskManager taskManager;
    final WorkerManager workerManager;
    final AssignmentRecordService assignmentRecords;
    final List<TaskDispatchBinding> dispatches;
    final TaskWorkerAssignListener assignListener;
    final WorkerControlService workerControlService;

    TaskSchedulingTestHarness() {
        this.taskStorage = new InMemoryTaskShellRuntimeStore();
        this.taskManager = new TaskManager(
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
        this.workerManager = new WorkerManager(
                new InMemoryWorkerDeclarationRuntimeStore(),
                new InMemoryWorkerRegistry(),
                new InMemoryWorkerScoreBandSlotRuntime());
        this.workerControlService = new WorkerControlService(
                workerManager,
                workerManager,
                new DefaultWorkerDispatchAvailabilityPolicy(workerManager, workerManager),
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop()
        );
        this.assignmentRecords = new AssignmentRecordService();
        this.dispatches = new ArrayList<>();
        installDefaultWorkerRegistrationSpine();

        SimpleTaskDispatchBinder binder = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                assignmentRecords,
                (context, bindings) -> dispatches.addAll(bindings)
        );
        this.assignListener = new TaskWorkerAssignListener(
                workerManager,
                binder,
                taskManager,
                taskManager.events(),
                TraceEventLogger.noop(),
                assignmentRecords,
                null,
                null,
                null,
                new DefaultSchedulingPlaneResolver()
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

    WorkerTestFixture addWorker(String workerId, String routingCode) {
        return addWorker(workerId, routingCode, Map.of());
    }

    WorkerTestFixture addStatelessWorker(String workerId, int maxConcurrentWork) {
        WorkerTestFixture worker = worker(workerId);
        worker.setMaxConcurrentWork(maxConcurrentWork);
        workerManager.addWorker(workerDeclaration(worker));
        refreshHeartbeatEvidence(worker);
        return worker;
    }

    WorkerTestFixture addWorker(String workerId,
                                String routingCode,
                                Map<String, String> attributes) {
        return addWorker(workerId, DEFAULT_WORKER_GROUP_ID, routingCode, attributes);
    }

    WorkerTestFixture addWorker(String workerId,
                                String workerGroupId,
                                String routingCode,
                                Map<String, String> attributes) {
        installWorkerGroup(workerGroupId);
        WorkerTestFixture worker = worker(workerId);
        worker.setWorkerGroupId(workerGroupId);
        worker.setAttributes(workerAttributes(routingCode, attributes));
        workerManager.addWorker(workerDeclaration(worker));
        refreshHeartbeatEvidence(worker);
        return worker;
    }

    WorkerStateProjectionResult applyWorkerStateReport(String workerId,
                                                       long stateVersion,
                                                       String state,
                                                       String reason) {
        WorkerStateReport.Builder builder = WorkerStateReport.builder(workerId, stateVersion, state);
        if (reason != null) {
            builder.reason(reason);
        }
        WorkerStateProjectionResult result = workerControlService.applyWorkerStateReport(builder.build());
        assertTrue(result.success());
        return result;
    }

    private static WorkerDeclarationRecord workerDeclaration(WorkerTestFixture worker) {
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
        );
    }

    private void refreshHeartbeatEvidence(WorkerTestFixture worker) {
        if (worker == null || worker.getLastHeartbeat() == null) {
            return;
        }
        long observedAtMillis = worker.getLastHeartbeat()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        workerManager.refreshWorkerHeartbeat(worker.getWorkerId(), observedAtMillis);
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

    AssignmentRecord selectionOutcome(String taskId) {
        return latestSelectionOutcome(taskId).orElseThrow();
    }

    private java.util.Optional<AssignmentRecord> latestSelectionOutcome(String taskId) {
        return assignmentRecords.getRecordsByTaskId(taskId).stream()
                .filter(record -> AssignmentType.WORKER_SELECTION.equals(record.getType()))
                .max(java.util.Comparator.comparing(AssignmentRecord::getAssignTime));
    }

    int selectionReasonCount(String taskId, String reason) {
        java.util.Optional<AssignmentRecord> outcome = latestSelectionOutcome(taskId);
        if (outcome.isEmpty()) {
            return 0;
        }
        Object reasons = outcome.orElseThrow().getContextSnapshot().get("rejectedCountByReason");
        if (!(reasons instanceof Map<?, ?> reasonCounts)) {
            return 0;
        }
        Object count = reasonCounts.get(reason);
        return count instanceof Number number ? number.intValue() : 0;
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

    private WorkerTestFixture worker(String workerId) {
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId(workerId);
        worker.setLastHeartbeat(LocalDateTime.now());
        worker.setOnlineStrategy("polling");
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
        installWorkerGroup(DEFAULT_WORKER_GROUP_ID);
    }

    private void installWorkerGroup(String workerGroupId) {
        workerManager.upsertWorkerGroup(WorkerGroupRecord.builder(workerGroupId)
                .projectCodes(List.of("demoApp"))
                .build());
        workerManager.bindNodeGroup(new NodeGroupBindingRecord(
                DEFAULT_ADAPTER_NODE_ID,
                workerGroupId,
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
