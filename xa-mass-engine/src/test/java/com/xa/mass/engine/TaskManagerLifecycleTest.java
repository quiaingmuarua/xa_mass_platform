package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerLifecycleTest {

    private RecordingTaskScheduler scheduler;
    private TaskStorage taskStorage;
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskStorage = new InMemoryTaskStorage();
        taskManager = new TaskManager(scheduler, taskStorage);
    }

    @Test
    void createTaskStartsAsNewAndPreservesTargets() {
        Task task = taskManager.createTask(buildRequest("task-create"));

        assertEquals(TaskStatus.NEW, task.getStatus());

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        assertEquals(2, messages.size());
        assertEquals("alpha", messages.get(0).getTarget());
        assertEquals("beta", messages.get(1).getTarget());
        assertEquals(task.getTid(), messages.get(0).getTaskId());
        assertEquals(task.getTid(), messages.get(1).getTaskId());
        assertNotEquals(messages.get(0).getMsgId(), messages.get(1).getMsgId());
    }

    @Test
    void taskCanMoveFromNewToReadyToPausedAndBackToReady() {
        Task task = taskManager.createTask(buildRequest("task-lifecycle"));

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);

        assertTrue(taskManager.resumeTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), scheduler.resumedTaskIds);
    }

    @Test
    void resumeTaskDetailedReportsReadyOutcome() {
        Task task = taskManager.createTask(buildRequest("task-resume-detailed"));
        assertTrue(taskManager.approveTask(task.getTid()));
        assertTrue(taskManager.pauseTask(task.getTid()));

        TaskResumeResult result = taskManager.resumeTaskDetailed(task.getTid());

        assertTrue(result.isSuccess());
        assertEquals(TaskResumeResult.Outcome.RESUMED_TO_READY, result.getOutcome());
        assertEquals(TaskStatus.READY, result.getStatus());
        assertNull(result.getTerminalReason());
    }

    @Test
    void blockedTaskCanBeApprovedBackToReady() {
        Task task = taskManager.createTask(buildRequest("task-blocked"));

        assertTrue(taskManager.rejectTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.REVIEW_REJECTED, taskManager.getTask(task.getTid()).getHoldReason());

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertNull(taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void taskReadyListenersRunOnApproveAndResume() {
        Task task = taskManager.createTask(buildRequest("task-ready-hook"));
        AtomicInteger notifications = new AtomicInteger();
        taskManager.addTaskReadyListener(t -> {
            if (task.getTid().equals(t.getTid())) {
                notifications.incrementAndGet();
            }
        });

        assertTrue(taskManager.approveTask(task.getTid()));
        assertTrue(taskManager.pauseTask(task.getTid()));
        assertTrue(taskManager.resumeTask(task.getTid()));

        assertEquals(2, notifications.get());
    }

    @Test
    void invalidActionsAreRejectedOutsideExpectedStates() {
        Task task = taskManager.createTask(buildRequest("task-invalid"));

        assertFalse(taskManager.pauseTask(task.getTid()));
        assertFalse(taskManager.resumeTask(task.getTid()));

        assertTrue(taskManager.approveTask(task.getTid()));
        assertFalse(taskManager.rejectTask(task.getTid()));
        assertFalse(taskManager.approveTask(task.getTid()));

        assertTrue(taskManager.cancelTask(task.getTid()));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, taskManager.getTask(task.getTid()).getTerminalReason());
        assertFalse(taskManager.resumeTask(task.getTid()));
    }

    @Test
    void createTaskRejectsWhenNoTargetsProvided() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("no-targets");
        dto.setProject("demoApp");
        dto.setRoutingCode("us");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke"));
        dto.setUserId("agent");
        dto.setTargetList(null);
        dto.setBatchSize(0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));
        assertTrue(error.getMessage().contains("target"));
    }

    @Test
    void createTaskPersistsRequestedBatchSize() {
        TaskCreateRequestDto dto = buildRequest("batch-size");
        dto.setBatchSize(3);

        Task task = taskManager.createTask(dto);

        assertEquals(3, task.getBatchSize());
    }

    @Test
    void deleteTaskRejectedForReadyTask() {
        Task task = taskManager.createTask(buildRequest("del-ready"));
        taskManager.approveTask(task.getTid()); // NEW -> READY

        assertFalse(taskManager.deleteTask(task.getTid()),
                "READY task must not be deletable");
        assertNotNull(taskManager.getTask(task.getTid()),
                "Task should still exist after rejected delete");
    }

    @Test
    void deleteTaskAllowedForNewTask() {
        Task task = taskManager.createTask(buildRequest("del-new"));
        assertTrue(taskManager.deleteTask(task.getTid()),
                "NEW task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForTerminalTask() {
        Task task = taskManager.createTask(buildRequest("del-terminal"));
        taskManager.approveTask(task.getTid());
        taskManager.cancelTask(task.getTid()); // -> TERMINAL

        assertTrue(taskManager.deleteTask(task.getTid()),
                "TERMINAL task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
    }

    @Test
    void handleTaskMessageResultMarksSuccessAndFinishesRunningTask() {
        Task task = taskManager.createTask(buildRequest("task-result-success"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(2, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMsgStatus.SUCCESS, taskManager.getTaskMessage(task.getTid(), messages.get(0).getMsgId()).getStatus());
        assertEquals(TaskMsgStatus.SUCCESS, taskManager.getTaskMessage(task.getTid(), messages.get(1).getMsgId()).getStatus());
    }

    @Test
    void handleTaskMessageResultEmitsRunningSuccessAndTerminalTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMsgId().equals(mdc.get("msgId"))
                            && "ASSIGNED".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMsgId().equals(mdc.get("msgId"))
                            && "RUNNING".equals(mdc.get("fromStatus"))
                            && "SUCCESS".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_TERMINAL_CLOSED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(mdc.get("terminalReason")));
        }
    }

    @Test
    void handleTaskMessageResultEmitsTaskProgressSnapshot() {
        Task task = taskManager.createTask(buildRequest("task-progress-snapshot", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));
            capture.assertHasEvent("TASK_PROGRESS_SNAPSHOT", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "FINALIZED_TO_TERMINAL".equals(mdc.get("resolutionOutcome"))
                            && "TERMINAL".equals(mdc.get("taskStatus"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(mdc.get("terminalReason"))
                            && "1".equals(mdc.get("totalMessages"))
                            && "1".equals(mdc.get("successMessages"))
                            && "0".equals(mdc.get("pendingMessages"))
                            && "100.0".equals(mdc.get("progressPercent")));
        }
    }

    @Test
    void handleTaskMessageResultMarksFailureAndKeepsExecutedCountAtSuccessOnly() {
        Task task = taskManager.createTask(buildRequest("task-result-failure"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.setMaxRetryCount(0);
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom"));

        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskMsgStatus.FAILED, updatedMessage.getStatus());
        assertEquals("boom", updatedMessage.getErrorMessage());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());
    }

    @Test
    void retryReusesSameTaskMessageAndFinalSuccessCountDoesNotInflate() {
        Task task = taskManager.createTask(buildRequest("task-result-retry", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        String msgId = message.getMsgId();
        message.markAsAssigned();
        message.setWorkerId("worker-1");
        message.setWorkerContextId("worker-context-1");
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), msgId, false, "boom-once"));

        List<TaskMsg> afterRetryMessages = taskManager.getTaskMessages(task.getTid());
        assertEquals(1, afterRetryMessages.size());
        TaskMsg retriedMessage = afterRetryMessages.get(0);
        assertEquals(msgId, retriedMessage.getMsgId());
        assertEquals(TaskMsgStatus.INIT, retriedMessage.getStatus());
        assertEquals(1, retriedMessage.getRetryCount());
        assertNull(retriedMessage.getFinalReason());
        assertNull(retriedMessage.getWorkerId());
        assertNull(retriedMessage.getWorkerContextId());
        assertNull(retriedMessage.getErrorMessage());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());
        assertEquals(0, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), msgId, true, "done-after-retry"));

        TaskMsg finalMessage = taskManager.getTaskMessage(task.getTid(), msgId);
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, finalMessage.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, finalMessage.getFinalReason());
        assertEquals(1, finalMessage.getRetryCount());
        assertEquals("done-after-retry", finalMessage.getResult());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    @Test
    void retryEmitsRetryResetTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.setWorkerId("worker-1");
        message.setWorkerContextId("worker-context-1");
        taskManager.updateTaskMessage(task.getTid(), message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom-once"));
            capture.assertHasEvent("TASK_MSG_RETRY_RESET", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMsgId().equals(mdc.get("msgId"))
                            && "1".equals(mdc.get("retryCount"))
                            && "INIT".equals(mdc.get("toStatus")));
        }
    }

    @Test
    void retryableFailurePublishesMessageFinalBeforeDispatchRequested() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.setWorkerId("worker-1");
        message.setWorkerContextId("worker-context-1");
        taskManager.updateTaskMessage(task.getTid(), message);

        List<String> events = new java.util.ArrayList<>();
        taskManager.addTaskMessageFinalListener((currentTask, currentMessage) ->
                events.add("message-final:" + currentMessage.getStatus()));
        taskManager.addTaskDispatchListener(currentTask ->
                events.add("dispatch:" + currentTask.getStatus()));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom-once"));

        assertEquals(List.of("message-final:FAILED", "dispatch:RUNNING"), events);
    }

    @Test
    void terminalCompletionSuppressesNonTerminalMessageFinalNotification() {
        Task task = taskManager.createTask(buildRequest("task-terminal-event-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        List<String> events = new java.util.ArrayList<>();
        taskManager.addTaskMessageFinalListener((currentTask, currentMessage) -> events.add("message-final"));
        taskManager.addTaskTerminalListener(currentTask -> events.add("terminal"));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));

        assertEquals(List.of("terminal"), events);
    }

    @Test
    void retryExhaustedFailureMarksAttemptAsBusinessFailure() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-exhausted-attempt", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.setMaxRetryCount(0);
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom-final"));

        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), message.getMsgId());
        assertNotNull(attempt);
        assertEquals(TaskMsgAttemptStatus.FAILED, attempt.getStatus());
        assertEquals(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, attempt.getFinalReason());
    }

    @Test
    void resolveTaskStateFromMessagesReportsNotFinalizedWhileMessagesRemainOpen() {
        Task task = taskManager.createTask(buildRequest("task-resolution-pending"));
        taskManager.approveTask(task.getTid());

        TaskStateResolutionResult result = taskManager.resolveTaskStateFromMessages(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.NOT_FINALIZED, result.getOutcome());
        assertEquals(TaskStatus.READY, result.getStatus());
        assertEquals(2, result.getTotalMessages());
        assertEquals(0, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertNull(result.getTerminalReason());
    }

    @Test
    void pausedTaskCompletesToTerminalWhenFinalResultArrives() {
        Task task = taskManager.createTask(buildRequest("task-paused-completion", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-while-paused"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void resolveTaskStateFromMessagesFinalizesRunningTaskWhenAllMessagesAreFinal() {
        Task task = taskManager.createTask(buildRequest("task-resolution-finalized"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1");
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2");

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateResolutionResult result = taskManager.resolveTaskStateFromMessages(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
    }

    @Test
    void resolveTaskStateFromMessagesReportsAlreadyFinalForManuallyCancelledTask() {
        Task task = taskManager.createTask(buildRequest("task-resolution-already-final", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskStateResolutionResult result = taskManager.resolveTaskStateFromMessages(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.ALREADY_FINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, result.getTerminalReason());
        assertEquals(1, result.getTotalMessages());
    }

    @Test
    void resumeTaskDetailedReportsTerminalOutcomeWhenPausedTaskAlreadyCompleted() {
        Task task = taskManager.createTask(buildRequest("task-paused-resume-terminal", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-while-paused"));

        task.setStatus(TaskStatus.PAUSED);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskResumeResult result = taskManager.resumeTaskDetailed(task.getTid());

        assertTrue(result.isSuccess());
        assertEquals(TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void openEndedTaskStaysNonTerminalUntilSealed() {
        TaskCreateRequestDto request = buildRequest("task-open-ended", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = taskManager.createTask(request);

        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());

        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));

        Task beforeSeal = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, beforeSeal.getStatus());
        assertNull(beforeSeal.getTerminalReason());
        assertEquals(TaskIntakeStatus.OPEN, beforeSeal.getIntakeStatus());

        assertTrue(taskManager.sealTask(task.getTid()));

        Task sealed = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, sealed.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, sealed.getTerminalReason());
        assertEquals(TaskIntakeStatus.SEALED, sealed.getIntakeStatus());
    }

    @Test
    void lateCallbackAfterCancelDoesNotMutateTerminalTask() {
        Task task = taskManager.createTask(buildRequest("task-cancel-late-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.cancelTask(task.getTid()));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "late-success"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
        // cancelTask now drains in-flight ASSIGNED messages to EXPIRED
        assertEquals(TaskMsgStatus.EXPIRED, updatedMessage.getStatus());
        assertEquals(0, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    @Test
    void lateCallbackEmitsIgnoredLateTrace() {
        Task task = taskManager.createTask(buildRequest("task-late-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.MANUAL_CANCELLED);
        taskManager.updateTask(task);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "late-success"));
            capture.assertHasEvent("CALLBACK_IGNORED_LATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMsgId().equals(mdc.get("msgId")));
        }
    }

    @Test
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-once"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom-twice"));

        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals("done-once", updatedMessage.getResult());
        assertNull(updatedMessage.getErrorMessage());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    @Test
    void duplicateCallbackEmitsIgnoredDuplicateTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-once"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMsgId().equals(mdc.get("msgId")));
        }
    }

    @Test
    void mixedFinalTaskMessagesProduceMixedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-result-mixed"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            msg.setMaxRetryCount(0);
            taskManager.updateTaskMessage(task.getTid(), msg);
        });

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), false, "boom"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MIXED_MESSAGE_RESULTS, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void allFailedTaskMessagesProduceFailedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-result-all-failed"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            msg.setMaxRetryCount(0);
            taskManager.updateTaskMessage(task.getTid(), msg);
        });

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), false, "boom-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), false, "boom-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void validateTaskStateReportsValidTerminalSuccessTask() {
        Task task = taskManager.createTask(buildRequest("task-validate-valid-terminal"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2"));

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertTrue(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertEquals(2, result.getTotalMessages());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    void validateTaskStateRejectsBlockedTaskWithoutHoldReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-blocked-hold-reason"));
        assertTrue(taskManager.rejectTask(task.getTid()));

        Task blocked = taskManager.getTask(task.getTid());
        blocked.setHoldReason(null);
        taskManager.updateTask(blocked);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.BLOCKED_HOLD_REASON_MISSING));
    }

    @Test
    void validateTaskStateRejectsCompletedMessageWithoutFinalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-message-final-reason", List.of("alpha")));
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done"));
        message.setFinalReason(null);
        taskManager.updateTaskMessage(task.getTid(), message);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_MISSING));
    }

    @Test
    void validateTaskStateFlagsActiveAttemptWithFinalMessage() {
        Task task = taskManager.createTask(buildRequest("task-validate-active-attempt-final-message", List.of("alpha")));
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done", TaskMsgFinalReason.BUSINESS_SUCCESS));
        taskManager.updateTaskMessage(task.getTid(), message);

        TaskMsgAttempt activeAttempt = new TaskMsgAttempt("attempt-1", task.getTid(), message.getMsgId(), 1);
        activeAttempt.setWorkerId("worker-1");
        assertTrue(activeAttempt.markLeased(java.time.LocalDateTime.now().plusMinutes(1)));
        taskManager.addTaskMessageAttempt(task.getTid(), message.getMsgId(), activeAttempt);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE));
    }

    @Test
    void validateTaskStateReportsNeedsResolutionWhenMessagesAreFinalButTaskIsStillRunning() {
        Task task = taskManager.createTask(buildRequest("task-validate-needs-resolution"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertTrue(result.isValid());
        assertTrue(result.isNeedsResolution());
        assertEquals(TaskStatus.RUNNING, result.getStatus());
        assertNull(result.getTerminalReason());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    void validateTaskStateEmitsValidationSummaryWhenResolutionIsNeeded() {
        Task task = taskManager.createTask(buildRequest("task-validate-needs-resolution-trace"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());
            assertTrue(result.isValid());
            assertTrue(result.isNeedsResolution());
            capture.assertHasEvent("TASK_STATE_VALIDATION_SUMMARY", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "RUNNING".equals(mdc.get("taskStatus"))
                            && "true".equals(mdc.get("valid"))
                            && "true".equals(mdc.get("needsResolution"))
                            && "0".equals(mdc.get("violationCount"))
                            && "ANOMALY".equals(mdc.get("result")));
        }
    }

    @Test
    void validateTaskStateRejectsTerminalTaskWithoutTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-missing-terminal-reason", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), message);
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertNull(result.getTerminalReason());
        assertTrue(result.getViolations().contains(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISSING));
    }

    @Test
    void validateTaskStateRejectsMismatchedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-reason-mismatch"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.markAsAssigned();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), false, "boom"));

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED));
    }

    @Test
    void customTerminalPolicyCanKeepTaskRunningEvenWhenMessagesAreFinal() {
        TaskManager policyAwareManager = new TaskManager(
                scheduler,
                new InMemoryTaskStorage(),
                (task, stats) -> TaskTerminalPolicyDecision.keepRunning()
        );
        Task task = policyAwareManager.createTask(buildRequest("task-policy-keep-running", List.of("alpha")));
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskMsg message = policyAwareManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        policyAwareManager.updateTaskMessage(task.getTid(), message);
        assertTrue(policyAwareManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult result = policyAwareManager.resolveTaskStateFromMessages(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.NOT_FINALIZED, result.getOutcome());
        assertEquals(TaskStatus.RUNNING, policyAwareManager.getTask(task.getTid()).getStatus());
        assertNull(policyAwareManager.getTask(task.getTid()).getTerminalReason());
    }

    @Test
    void customTerminalPolicyCanForceTerminalBeforeAllMessagesAreFinal() {
        TaskTerminalPolicy runtimeLimitPolicy = (task, stats) ->
                TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MAX_RUNTIME_REACHED);
        TaskManager policyAwareManager = new TaskManager(
                scheduler,
                new InMemoryTaskStorage(),
                runtimeLimitPolicy
        );
        Task task = policyAwareManager.createTask(buildRequest("task-policy-force-terminal"));
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult result = policyAwareManager.resolveTaskStateFromMessages(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, result.getTerminalReason());
        assertEquals(TaskStatus.TERMINAL, policyAwareManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, policyAwareManager.getTask(task.getTid()).getTerminalReason());
    }

    // ---- Bug1: READY/RUNNING → BLOCKED (blockTask) ----

    // ---- Open intake terminal validation ----

    @Test
    void validateTaskStateAllowsRuntimeLimitClosureForOpenIntakeTask() {
        TaskTerminalPolicy runtimeLimitPolicy = (task, stats) ->
                TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MAX_RUNTIME_REACHED);
        TaskManager policyAwareManager = new TaskManager(
                scheduler,
                new InMemoryTaskStorage(),
                runtimeLimitPolicy
        );

        TaskCreateRequestDto request = buildRequest("task-open-intake-runtime-limit", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = policyAwareManager.createTask(request);
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult resolutionResult = policyAwareManager.resolveTaskStateFromMessages(task.getTid());
        TaskStateValidationResult validationResult = policyAwareManager.validateTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, resolutionResult.getOutcome());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, resolutionResult.getTerminalReason());
        assertTrue(validationResult.isValid());
        assertFalse(validationResult.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, validationResult.getStatus());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, validationResult.getTerminalReason());
        assertFalse(validationResult.getViolations().contains(
                TaskStateValidationResult.ViolationCode.OPEN_INTAKE_FINALIZED_NON_MANUALLY));
    }

    @Test
    void validateTaskStateDoesNotFlagOpenIntakeViolationForPolicyDrivenTerminalReasons() {
        List<TaskTerminalReason> policyDrivenReasons = List.of(
                TaskTerminalReason.MAX_RUNTIME_REACHED,
                TaskTerminalReason.SUCCESS_RATE_REACHED,
                TaskTerminalReason.RETRY_BUDGET_EXHAUSTED
        );

        for (TaskTerminalReason terminalReason : policyDrivenReasons) {
            TaskCreateRequestDto request = buildRequest("task-open-intake-" + terminalReason.name(), List.of("alpha"));
            request.setOpenEnded(true);
            Task task = taskManager.createTask(request);
            task.setStatus(TaskStatus.TERMINAL);
            task.setTerminalReason(terminalReason);
            taskManager.updateTask(task);

            TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

            assertTrue(result.isValid(), terminalReason.name());
            assertFalse(result.isNeedsResolution(), terminalReason.name());
            assertEquals(TaskStatus.TERMINAL, result.getStatus(), terminalReason.name());
            assertEquals(terminalReason, result.getTerminalReason(), terminalReason.name());
            assertFalse(result.getViolations().contains(
                    TaskStateValidationResult.ViolationCode.OPEN_INTAKE_FINALIZED_NON_MANUALLY), terminalReason.name());
        }
    }

    @Test
    void blockReadyTaskTransitionsToBlocked() {
        Task task = taskManager.createTask(buildRequest("block-ready"));
        taskManager.approveTask(task.getTid()); // NEW → READY

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.MANUAL_BLOCKED, taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void blockRunningTaskTransitionsToBlocked() {
        Task task = taskManager.createTask(buildRequest("block-running"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockedTaskViaBlockTaskCanBeApprovedBackToReady() {
        Task task = taskManager.createTask(buildRequest("block-then-approve"));
        taskManager.approveTask(task.getTid()); // READY
        taskManager.blockTask(task.getTid());   // BLOCKED

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockTaskRejectedForNewAndTerminalTasks() {
        Task newTask = taskManager.createTask(buildRequest("block-new"));
        assertFalse(taskManager.blockTask(newTask.getTid()), "NEW task cannot be blocked via blockTask");

        taskManager.approveTask(newTask.getTid());
        taskManager.cancelTask(newTask.getTid()); // → TERMINAL
        assertFalse(taskManager.blockTask(newTask.getTid()), "TERMINAL task cannot be blocked");
    }

    // ---- Bug2: TaskMsg.EXPIRED — expireTaskMessage ----

    @Test
    void expireAssignedMessageTransitionsToExpiredAndTaskAutoCompletes() {
        Task task = taskManager.createTask(buildRequest("expire-msg", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned(); // ASSIGNED
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMsgId()));

        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskMsgStatus.EXPIRED, updated.getStatus());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, updated.getFinalReason());

        // All messages now final → task should auto-terminate
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
    }

    @Test
    void expireRunningMessageTransitionsToExpired() {
        Task task = taskManager.createTask(buildRequest("expire-running", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.markAsRunning(); // RUNNING
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMsgId()));
        assertEquals(TaskMsgStatus.EXPIRED,
                taskManager.getTaskMessage(task.getTid(), message.getMsgId()).getStatus());
    }

    @Test
    void expireInitOrBindingMessageIsRejected() {
        Task task = taskManager.createTask(buildRequest("expire-init", List.of("alpha")));
        taskManager.approveTask(task.getTid());

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        // message is in INIT state — cannot be expired (never dispatched)
        assertFalse(taskManager.expireTaskMessage(task.getTid(), message.getMsgId()));
        assertEquals(TaskMsgStatus.INIT,
                taskManager.getTaskMessage(task.getTid(), message.getMsgId()).getStatus());
    }

    // ---- Bug3: cancelTask drains in-flight messages ----

    @Test
    void cancelTaskDrainsAllNonFinalMessagesToTerminalState() {
        Task task = taskManager.createTask(buildRequest("cancel-cleanup", List.of("a", "b", "c")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        // msg[0]: leave in INIT
        // msg[1]: leave in INIT
        taskManager.updateTaskMessage(task.getTid(), messages.get(1));
        // msg[2]: advance to ASSIGNED
        messages.get(2).markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), messages.get(2));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMsg msg0 = taskManager.getTaskMessage(task.getTid(), messages.get(0).getMsgId());
        TaskMsg msg1 = taskManager.getTaskMessage(task.getTid(), messages.get(1).getMsgId());
        TaskMsg msg2 = taskManager.getTaskMessage(task.getTid(), messages.get(2).getMsgId());

        // INIT → FAILED; ASSIGNED → EXPIRED
        assertTrue(msg0.isCompleted(), "INIT message should be in final state after cancel");
        assertEquals(TaskMsgStatus.FAILED, msg0.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, msg0.getFinalReason());
        assertTrue(msg1.isCompleted(), "INIT message should be in final state after cancel");
        assertEquals(TaskMsgStatus.FAILED, msg1.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, msg1.getFinalReason());
        assertTrue(msg2.isCompleted(), "ASSIGNED message should be in final state after cancel");
        assertEquals(TaskMsgStatus.EXPIRED, msg2.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, msg2.getFinalReason());
    }

    // ---- Bug4: Task.isCompleted() only returns true when status is final ----

    @Test
    void isCompletedReturnsTrueOnlyWhenTaskStatusIsFinal() {
        Task task = taskManager.createTask(buildRequest("is-completed", List.of("alpha")));

        // NEW: not final
        assertFalse(task.isCompleted());

        // Force taskSuccessNumber so that taskNonSuccessNumber == 0 while status is still READY
        taskManager.approveTask(task.getTid());
        Task ready = taskManager.getTask(task.getTid());
        ready.setTaskSuccessNumber(ready.getTaskEligibleNumber()); // all "succeeded" in the counter
        taskManager.updateTask(ready);

        // Status is READY, not TERMINAL — must still report not completed
        assertFalse(taskManager.getTask(task.getTid()).isCompleted(),
                "Task with all messages 'succeeded' in counter but status=READY must not be completed");

        // After cancellation the task is TERMINAL — must report completed
        taskManager.cancelTask(task.getTid());
        assertTrue(taskManager.getTask(task.getTid()).isCompleted());
    }

    private TaskCreateRequestDto buildRequest(String taskName) {
        return buildRequest(taskName, List.of("alpha", "beta"));
    }

    private TaskCreateRequestDto buildRequest(String taskName, List<String> targets) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setRoutingCode("us");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke"));
        dto.setUserId("agent");
        dto.setTargetList(targets);
        dto.setBatchSize(1);
        return dto;
    }

    private static class RecordingTaskScheduler implements TaskScheduler {
        private final List<String> pausedTaskIds = new java.util.ArrayList<>();
        private final List<String> resumedTaskIds = new java.util.ArrayList<>();
        private final List<String> cancelledTaskIds = new java.util.ArrayList<>();
        private int completedTaskMsgCount;
        private int failedTaskMsgCount;

        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
            completedTaskMsgCount++;
            return true;
        }

        @Override
        public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
            failedTaskMsgCount++;
            return true;
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            cancelledTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            pausedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            resumedTaskIds.add(taskId);
            return true;
        }
    }
}
