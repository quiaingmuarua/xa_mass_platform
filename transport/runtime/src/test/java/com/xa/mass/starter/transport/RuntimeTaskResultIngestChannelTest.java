package com.xa.mass.starter.transport;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.transport.model.TaskResultReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTaskResultIngestChannelTest {

    private RecordingTaskScheduler scheduler;
    private TaskManager taskManager;
    private RuntimeTaskResultIngestChannel channel;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskManager = new TaskManager(scheduler, new InMemoryTaskStorage());
        channel = new RuntimeTaskResultIngestChannel(taskManager);
    }

    @Test
    void successResponseUpdatesStoredTaskMessage() {
        Task task = createRunningTask("task-success");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);

        boolean handled = channel.ingest(report(task, taskMsg, "SUCCESS", "ok", null));

        assertTrue(handled);
        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok", updated.getOutput().get("mockData"));
        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMessageId());
        assertNotNull(attempt);
        assertEquals("SUCCESS", attempt.getOutput().get("status"));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void failureResponseMarksTaskMessageFailed() {
        Task task = createRunningTask("task-failure");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);
        taskMsg.setMaxRetryCount(0);
        taskManager.updateTaskMessage(task.getTid(), taskMsg);

        boolean handled = channel.ingest(report(task, taskMsg, "FAILED", "boom", "RATE_LIMITED"));

        assertTrue(handled);
        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.FAILED, updated.getStatus());
        assertEquals("boom", updated.getErrorMessage());
        assertEquals("RATE_LIMITED", updated.getErrorCode());
        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMessageId());
        assertNotNull(attempt);
        assertEquals("RATE_LIMITED", attempt.getErrorCode());
        assertEquals("FAILED", attempt.getOutput().get("status"));
    }

    @Test
    void duplicateResponseKeepsFirstFinalResultAndStillReturnsHandled() {
        Task task = createRunningTask("task-duplicate");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);

        boolean firstHandled = channel.ingest(report(task, taskMsg, "SUCCESS", "ok", null));
        boolean secondHandled = channel.ingest(report(task, taskMsg, "FAILED", "boom", null));

        assertTrue(firstHandled);
        assertTrue(secondHandled);
        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertNull(updated.getErrorMessage());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    @Test
    void transportNeutralResultReportCanBeIngestedWithoutWebSocketMessageObject() {
        Task task = createRunningTask("task-transport-neutral");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);

        boolean handled = channel.ingest(new TaskResultReport(
                task.getTid(),
                taskMsg.getMessageId(),
                true,
                "ok-from-report",
                null,
                Map.of("status", "SUCCESS", "mockData", "ok-from-report")
        ));

        assertTrue(handled);
        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok-from-report", updated.getOutput().get("mockData"));
    }

    private Task createRunningTask(String taskName) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "routingCode", "us",
                "_sdk", java.util.Map.of("eventCode", "crawler.fetch-page")
        ));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setInputs(List.of(Map.of("target", "alpha")));
        Task task = taskManager.createTask(dto);
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);
        taskMsg.applyLatestAttemptProjection("worker-1", "worker-context-1", "batch-0");
        taskMsg.markAsAssigned();
        taskManager.updateTaskMessage(task.getTid(), taskMsg);

        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-" + taskMsg.getMessageId() + "-1",
                task.getTid(), taskMsg.getMessageId(), 1);
        attempt.setWorkerId("worker-1");
        attempt.setWorkerContextId("worker-context-1");
        attempt.setBatchId("batch-0");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskManager.addTaskMessageAttempt(task.getTid(), taskMsg.getMessageId(), attempt);
        return task;
    }

    private TaskResultReport report(Task task, TaskMsg taskMsg, String status, String detail, String errorCode) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mockData", detail);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        return new TaskResultReport(
                task.getTid(),
                taskMsg.getMessageId(),
                "SUCCESS".equalsIgnoreCase(status),
                detail,
                errorCode,
                payload
        );
    }

    private static class RecordingTaskScheduler implements TaskScheduler {
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
}
