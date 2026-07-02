package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerLifecycleTest {

    private InMemoryTaskShellRuntimeStore taskStorage;
    private InMemoryTaskRuntime taskRuntime;
    private TaskManager taskManager;
    private TaskRuntimeServingLane taskRuntimeServingLane;

    @BeforeEach
    void setUp() {
        taskStorage = new InMemoryTaskShellRuntimeStore();
        taskRuntime = new InMemoryTaskRuntime();
        taskManager = new TaskManager(
                taskStorage,
                taskStorage,
                new ContractAwareTaskTerminalPolicy(),
                null);
        taskRuntimeServingLane = TaskRuntimeServingLaneTestSupport.forTaskManager(
                taskRuntime,
                taskRuntime,
                taskRuntime,
                taskRuntime,
                taskRuntime,
                taskManager,
                300L,
                TaskManager.MAX_INGEST_BATCH_ITEMS,
                86_400_000L);
        taskManager.installTaskRuntimeServingLane(taskRuntimeServingLane);
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    @Test
    void createTaskStartsAsNewAndEnqueuesRuntimeWorkWithoutProjectionRows() {
        Task task = createTask(buildRequest("task-create"), List.of(
                Map.<String, Object>of("target", "alpha"),
                Map.<String, Object>of("target", "beta")
        ));

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskContract.BATCH, task.getContract());
        assertEquals(TaskWorkloadClass.BULK, task.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertNotNull(task.getProjectRef());
        assertEquals("demoApp", task.getProjectRef().getCode());
        assertNotNull(task.getUser());
        assertEquals("agent", task.getUser().getUserId());
        assertEquals(2, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
    }

    @Test
    void noOldRuntimeConstructorFailsFastWithoutServingLaneInsteadOfUsingLegacySentinels() {
        InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
        TaskManager manager = new TaskManager(
                storage,
                storage,
                new ContractAwareTaskTerminalPolicy(),
                null);

        try {
            TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
            request.setProject("demoApp");
            request.setUserId("agent");
            TaskCommandOutcome create = manager.createTaskShell(request);
            assertTrue(create.accepted());
            Task task = manager.getTask(create.taskId());
            assertNotNull(task);
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> manager.appendTaskItems(task.getTid(), List.of(Map.of("target", "alpha"))));
            assertTrue(exception.getMessage().contains("requires TaskRuntimeServingLane"));
            assertTrue(exception.getMessage().contains("old TaskWorkRuntime/TaskResultRuntime path has been deleted"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void payloadRefAppendEnqueuesRuntimeWorkWithoutMessageInputPayload() {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("payload-ref-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.SESSION);

        TaskCommandOutcome create = taskManager.createTaskShell(dto);
        assertTrue(create.accepted());
        Task task = taskManager.getTask(create.taskId());
        assertNotNull(task);
        String payloadRef = "s3://bucket/payloads/demo-1.json";

        TaskAppendOutcome receipt = taskManager.appendTaskItems(
                task.getTid(),
                List.of(Map.<String, Object>of(
                        "eventCode", "demo.event",
                        "payloadRef", payloadRef)));

        assertEquals(1, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
        assertTrue(taskManager.approveTask(task.getTid()).accepted());
        ClaimedWorkItem claimed = TaskRuntimeClaimTestSupport.claimSingle(
                taskRuntimeServingLane,
                taskManager.getWorkLeaseSeconds(),
                task.getTid(),
                "group-1",
                "worker-payload-ref",
                "batch-0");
        assertEquals(receipt.messageIds().getFirst(), claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payloadJson().isEmpty());
    }

    @Test
    void batchTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("file-ingest-before-approval");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.BATCH);

        TaskCommandOutcome create = taskManager.createTaskShell(dto);
        assertTrue(create.accepted());
        Task task = taskManager.getTask(create.taskId());
        assertNotNull(task);
        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        TaskAppendOutcome append = taskManager.appendTaskItems(task.getTid(), List.of(
                Map.<String, Object>of("target", "alpha"),
                Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());

        assertEquals(2, append.acceptedCount());
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
        assertEquals(0, dispatchRequests.get());
    }

    private Task createTask(TaskShellCreateRequestDto request, List<Map<String, Object>> inputs) {
        TaskContract contract = request.getContract() != null ? request.getContract() : TaskContract.BATCH;
        TaskCommandOutcome create = taskManager.createTaskShell(request);
        assertTrue(create.accepted());
        Task task = taskManager.getTask(create.taskId());
        assertNotNull(task);
        if (inputs != null && !inputs.isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), inputs);
        }
        if (contract != TaskContract.SESSION) {
            assertTrue(taskManager.sealTask(task.getTid()).accepted());
        }
        return taskManager.getTask(task.getTid());
    }

    private TaskShellCreateRequestDto buildRequest(String taskName) {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(Map.of("textContent", "smoke", "routingCode", "us"));
        dto.setUserId("agent");
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(3);
        dto.setExecutionSpec(spec);
        return dto;
    }

}
