package com.xa.mass.runtime.redis;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRuntimeTraceIntegrationTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisTaskWorkRuntime runtime;
    private InMemoryTaskStorage taskStorage;
    private TaskManager taskManager;
    private TaskCommandService taskCommands;
    private TaskQueryService taskQueries;
    private TaskManagerResultIngestFacade resultFacade;
    private TaskRuntimeMaintenancePort maintenancePort;
    private RecordingExecutionEventSink traceSink;
    private AtomicReference<Instant> now;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            redisConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for integration test: " + ex.getMessage());
            throw ex;
        }
        now = new AtomicReference<>(Instant.parse("2026-05-06T00:00:00Z"));
        runtime = new RedisTaskWorkRuntime(
                redisConnection,
                new RedisTaskWorkKeyspace("xa:mass:test:redis-trace:" + UUID.randomUUID()),
                1024,
                now::get
        );
        taskStorage = new InMemoryTaskStorage();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(new NoopTaskScheduler(), taskStorage, taskStorage, runtime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        resultFacade = new TaskManagerResultIngestFacade(taskManager);
        maintenancePort = taskManager;
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
        if (redisConnection != null && redisConnection.isOpen()) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void successfulResultOnRedisRuntimeFinalizesTaskAndEmitsCanonicalTrace() {
        RunningTaskFixture fixture = createAssignedTask("redis-success-trace", 0);

        boolean handled = resultFacade.handleTaskMessageResult(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                true,
                "ok-redis",
                null,
                Map.of("status", "SUCCESS", "mockData", "ok-redis")
        );

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.task().getTid(), fixture.message().getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.task().getTid()).getStatus());
        assertFalse(runtime.hasReadyWork(fixture.task().getTid()));
        assertTrue(runtime.activeLeases(fixture.task().getTid()).isEmpty());

        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_ACCEPTED);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_ATTEMPT_CLOSED);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_LOGICALLY_FINAL);
        assertTraceContainsTaskEvent(fixture.task().getTid(), ExecutionEventType.TASK_TERMINAL_CLOSED);
    }

    @Test
    void duplicateCallbackOnRedisRuntimeKeepsFirstFinalResultAndEmitsDuplicateTrace() {
        RunningTaskFixture fixture = createAssignedTask("redis-duplicate-trace", 0);

        boolean firstHandled = resultFacade.handleTaskMessageResult(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                true,
                "ok-first",
                null,
                Map.of("status", "SUCCESS", "mockData", "ok-first")
        );
        boolean duplicateHandled = resultFacade.handleTaskMessageResult(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                false,
                "boom-duplicate",
                "IGNORED_DUPLICATE",
                Map.of("status", "FAILED", "mockData", "boom-duplicate")
        );

        assertTrue(firstHandled);
        assertTrue(duplicateHandled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.task().getTid(), fixture.message().getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-first", updated.getOutput().get("mockData"));

        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_ACCEPTED);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_IGNORED_DUPLICATE);
    }

    @Test
    void callbackAfterRuntimeLeaseAlreadyConvergedIsRejectedAndEmitsNoActiveLeaseTrace() {
        RunningTaskFixture fixture = createAssignedTask("redis-no-active-lease-trace", 0);

        ResultApplyStatus runtimeStatus = runtime.applyResult(TaskWorkResult.success(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                fixture.claimedWork().leaseToken(),
                "runtime-only-success",
                Map.of("status", "SUCCESS", "mockData", "runtime-only-success")
        )).status();

        boolean handled = resultFacade.handleTaskMessageResult(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                true,
                "late-after-runtime",
                null,
                Map.of("status", "SUCCESS", "mockData", "late-after-runtime")
        );

        assertEquals(ResultApplyStatus.SUCCESS_APPLIED, runtimeStatus);
        assertFalse(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.task().getTid(), fixture.message().getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, updated.getStatus());
        assertTrue(runtime.activeLeases(fixture.task().getTid()).isEmpty());

        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_REJECTED_NO_ACTIVE_LEASE);
        assertTraceDoesNotContain(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_ACCEPTED);
    }

    @Test
    void callbackOnAlreadyTerminalTaskIsIgnoredAsLateAndEmitsLateTrace() {
        RunningTaskFixture fixture = createAssignedTask("redis-late-callback-trace", 0);
        fixture.task().setStatus(TaskStatus.TERMINAL);
        taskStorage.updateTask(fixture.task());

        boolean handled = resultFacade.handleTaskMessageResult(
                fixture.task().getTid(),
                fixture.message().getMessageId(),
                true,
                "late-terminal",
                null,
                Map.of("status", "SUCCESS", "mockData", "late-terminal")
        );

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.task().getTid(), fixture.message().getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, updated.getStatus());
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.task().getTid()).getStatus());
        assertEquals(1, runtime.activeLeases(fixture.task().getTid()).size());

        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_IGNORED_LATE);
        assertTraceDoesNotContain(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.CALLBACK_ACCEPTED);
    }

    @Test
    void leaseExpiryOnRedisRuntimeRequeuesWorkAndEmitsRetryTrace() {
        RunningTaskFixture fixture = createAssignedTask("redis-expire-retry-trace", 1);

        boolean expired = maintenancePort.expireTaskMessage(fixture.task().getTid(), fixture.message().getMessageId());

        assertTrue(expired);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.task().getTid(), fixture.message().getMessageId());
        assertEquals(TaskMsgStatus.INIT, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertTrue(runtime.hasReadyWork(fixture.task().getTid()));
        assertEquals(1, runtime.stats(fixture.task().getTid()).readyCount());

        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.LEASE_EXPIRED);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_STATUS_TRANSITION);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_RETRY_RESET);
        assertTraceContains(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_ATTEMPT_CLOSED);
        assertTraceDoesNotContain(fixture.task().getTid(), fixture.message().getMessageId(), ExecutionEventType.TASK_MSG_LOGICALLY_FINAL);
    }

    private RunningTaskFixture createAssignedTask(String taskName, int maxRetryCount) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(Map.of(
                "textContent", "hello",
                "routingCode", "us",
                "_sdk", Map.of("eventCode", "crawler.fetch-page")
        ));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setInputs(List.of(Map.of("target", "alpha")));
        Task task = taskCommands.createTask(dto);
        taskCommands.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskQueries.getTaskMessageSnapshot(task.getTid(), 1).messages().get(0);
        message.setMaxRetryCount(maxRetryCount);
        taskStorage.updateTaskMessage(task.getTid(), message);

        ClaimedTaskWork claimed = runtime.claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-1", "worker-context-1", "batch-0", 1)),
                1,
                300
        ).get(0);
        message.applyLatestAttemptProjection(claimed.workerId(), claimed.workerContextId(), claimed.batchId());
        message.markAsAssigned();
        taskStorage.updateTaskMessage(task.getTid(), message);

        TaskMsgAttempt attempt = new TaskMsgAttempt(
                "attempt-" + message.getMessageId() + "-1",
                task.getTid(),
                message.getMessageId(),
                1
        );
        attempt.setWorkerId(claimed.workerId());
        attempt.setWorkerContextId(claimed.workerContextId());
        attempt.setBatchId(claimed.batchId());
        LocalDateTime leaseExpire = LocalDateTime.ofInstant(claimed.leaseExpireAt(), ZoneId.systemDefault());
        assertTrue(attempt.markLeased(leaseExpire));
        assertTrue(attempt.markDispatched());
        taskStorage.addTaskMessageAttempt(task.getTid(), message.getMessageId(), attempt);
        return new RunningTaskFixture(task, message, attempt, claimed);
    }

    private void assertTraceContains(String taskId, String messageId, ExecutionEventType eventType) {
        assertTrue(traceSink.events.stream().anyMatch(event ->
                        event.getEventType() == eventType
                                && event.getIdentity() != null
                                && taskId.equals(event.getIdentity().taskId())
                                && messageId.equals(event.getIdentity().messageId())),
                "Expected trace event " + eventType + " for taskId=" + taskId + ", messageId=" + messageId);
    }

    private void assertTraceContainsTaskEvent(String taskId, ExecutionEventType eventType) {
        assertTrue(traceSink.events.stream().anyMatch(event ->
                        event.getEventType() == eventType
                                && event.getIdentity() != null
                                && taskId.equals(event.getIdentity().taskId())),
                "Expected task-level trace event " + eventType + " for taskId=" + taskId);
    }

    private void assertTraceDoesNotContain(String taskId, String messageId, ExecutionEventType eventType) {
        assertFalse(traceSink.events.stream().anyMatch(event ->
                        event.getEventType() == eventType
                                && event.getIdentity() != null
                                && taskId.equals(event.getIdentity().taskId())
                                && messageId.equals(event.getIdentity().messageId())),
                "Did not expect trace event " + eventType + " for taskId=" + taskId + ", messageId=" + messageId);
    }

    private record RunningTaskFixture(Task task, TaskMsg message, TaskMsgAttempt attempt, ClaimedTaskWork claimedWork) {
    }

    private static final class NoopTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }

    private static final class RecordingExecutionEventSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void emit(ExecutionEvent event) {
            events.add(event);
        }
    }
}

