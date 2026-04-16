package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.engine.model.TaskTerminalPolicyDecision;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.storage.TaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
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

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
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
        dto.setCountryCode("us");
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
            msg.transitionTo(TaskMsgStatus.BINDING);
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
    void handleTaskMessageResultMarksFailureAndKeepsExecutedCountAtSuccessOnly() {
        Task task = taskManager.createTask(buildRequest("task-result-failure"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
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
        message.transitionTo(TaskMsgStatus.BINDING);
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
        assertEquals("worker-1", retriedMessage.getWorkerId());
        assertEquals("worker-context-1", retriedMessage.getWorkerContextId());
        assertNull(retriedMessage.getErrorMessage());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());
        assertEquals(0, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), msgId, true, "done-after-retry"));

        TaskMsg finalMessage = taskManager.getTaskMessage(task.getTid(), msgId);
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, finalMessage.getStatus());
        assertEquals(1, finalMessage.getRetryCount());
        assertEquals("done-after-retry", finalMessage.getResult());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
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
        message.transitionTo(TaskMsgStatus.BINDING);
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
            msg.transitionTo(TaskMsgStatus.BINDING);
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
        message.transitionTo(TaskMsgStatus.BINDING);
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
        message.transitionTo(TaskMsgStatus.BINDING);
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
    void lateCallbackAfterCancelDoesNotMutateTerminalTask() {
        Task task = taskManager.createTask(buildRequest("task-cancel-late-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
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
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
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
    void mixedFinalTaskMessagesProduceMixedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-result-mixed"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.transitionTo(TaskMsgStatus.BINDING);
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
            msg.transitionTo(TaskMsgStatus.BINDING);
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
            msg.transitionTo(TaskMsgStatus.BINDING);
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
    void validateTaskStateReportsNeedsResolutionWhenMessagesAreFinalButTaskIsStillRunning() {
        Task task = taskManager.createTask(buildRequest("task-validate-needs-resolution"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> {
            msg.transitionTo(TaskMsgStatus.BINDING);
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
    void validateTaskStateRejectsTerminalTaskWithoutTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-missing-terminal-reason", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
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
            msg.transitionTo(TaskMsgStatus.BINDING);
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
        message.transitionTo(TaskMsgStatus.BINDING);
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

    @Test
    void blockReadyTaskTransitionsToBlocked() {
        Task task = taskManager.createTask(buildRequest("block-ready"));
        taskManager.approveTask(task.getTid()); // NEW → READY

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
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
        message.transitionTo(TaskMsgStatus.BINDING);
        message.markAsAssigned(); // ASSIGNED
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMsgId()));

        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskMsgStatus.EXPIRED, updated.getStatus());

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
        message.transitionTo(TaskMsgStatus.BINDING);
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
        // msg[1]: advance to BINDING
        messages.get(1).transitionTo(TaskMsgStatus.BINDING);
        taskManager.updateTaskMessage(task.getTid(), messages.get(1));
        // msg[2]: advance to ASSIGNED
        messages.get(2).transitionTo(TaskMsgStatus.BINDING);
        messages.get(2).markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), messages.get(2));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMsg msg0 = taskManager.getTaskMessage(task.getTid(), messages.get(0).getMsgId());
        TaskMsg msg1 = taskManager.getTaskMessage(task.getTid(), messages.get(1).getMsgId());
        TaskMsg msg2 = taskManager.getTaskMessage(task.getTid(), messages.get(2).getMsgId());

        // INIT and BINDING → FAILED; ASSIGNED → EXPIRED
        assertTrue(msg0.isCompleted(), "INIT message should be in final state after cancel");
        assertEquals(TaskMsgStatus.FAILED, msg0.getStatus());
        assertTrue(msg1.isCompleted(), "BINDING message should be in final state after cancel");
        assertEquals(TaskMsgStatus.FAILED, msg1.getStatus());
        assertTrue(msg2.isCompleted(), "ASSIGNED message should be in final state after cancel");
        assertEquals(TaskMsgStatus.EXPIRED, msg2.getStatus());
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
        dto.setCountryCode("us");
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
