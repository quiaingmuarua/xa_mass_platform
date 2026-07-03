package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerLifecycleTest {

    private InMemoryTaskShellRuntimeStore taskStorage;
    private InMemoryTaskWorkRuntime taskWorkRuntime;
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        taskStorage = new InMemoryTaskShellRuntimeStore();
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        taskManager = new TaskManager(taskStorage, taskWorkRuntime, new InMemoryTaskResultRuntime(), null);
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
        assertEquals(2, taskWorkRuntime.stats(task.getTid()).readyCount());
    }

    @Test
    void payloadRefIngressEnqueuesRuntimeWorkWithoutMessageInputPayload() {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("payload-ref-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.SESSION);

        Task task = taskManager.createTaskShell(dto);
        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-1.json";

        taskManager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 5);

        assertEquals(1, taskWorkRuntime.stats(task.getTid()).readyCount());
        ClaimedTaskWork claimed = taskWorkRuntime.claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-payload-ref", "batch-0", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        ).getFirst();
        assertEquals(messageId, claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payload().isEmpty());
    }

    @Test
    void batchTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("file-ingest-before-approval");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.BATCH);

        Task task = taskManager.createTaskShell(dto);
        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                Map.<String, Object>of("target", "alpha"),
                Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());

        assertEquals(2, added);
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, taskWorkRuntime.stats(task.getTid()).readyCount());
        assertEquals(0, dispatchRequests.get());
    }

    private Task createTask(TaskShellCreateRequestDto request, List<Map<String, Object>> inputs) {
        TaskContract contract = request.getContract() != null ? request.getContract() : TaskContract.BATCH;
        Task task = taskManager.createTaskShell(request);
        if (inputs != null && !inputs.isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), inputs);
        }
        if (contract != TaskContract.SESSION) {
            assertTrue(taskManager.sealTask(task.getTid()));
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
