package com.xa.mass.starter;

import com.google.gson.Gson;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayTaskResultHandlerTest {

    private RecordingTaskScheduler scheduler;
    private TaskManager taskManager;
    private GatewayTaskResultHandler handler;
    private Gson gson;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskManager = new TaskManager(scheduler, new InMemoryTaskStorage());
        handler = new GatewayTaskResultHandler(taskManager);
        gson = new Gson();
    }

    @Test
    void successResponseUpdatesStoredTaskMessageAndReturnsAck() {
        Task task = createRunningTask("task-success");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);

        List<MassMessage> responses = handler.handle(message(task, taskMsg, "SUCCESS", "ok"));

        assertEquals(1, responses.size());
        assertEquals(MessageType.TASK, responses.get(0).getMsgType());
        assertTrue(responses.get(0).isResponse());
        assertEquals(200, gson.fromJson(responses.get(0).getPayload(), MessageAckPayload.class).getCode());

        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMsgId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok", updated.getResult());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok", updated.getOutput().get("mockData"));
        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMsgId());
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

        handler.handle(message(task, taskMsg, "FAILED", "boom", "RATE_LIMITED"));

        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMsgId());
        assertEquals(TaskMsgStatus.FAILED, updated.getStatus());
        assertEquals("boom", updated.getErrorMessage());
        assertEquals("RATE_LIMITED", updated.getErrorCode());
        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMsgId());
        assertNotNull(attempt);
        assertEquals("RATE_LIMITED", attempt.getErrorCode());
        assertEquals("FAILED", attempt.getOutput().get("status"));
    }

    @Test
    void duplicateResponseKeepsFirstFinalResultAndReturnsAck() {
        Task task = createRunningTask("task-duplicate");
        TaskMsg taskMsg = taskManager.getTaskMessages(task.getTid()).get(0);

        List<MassMessage> firstResponses = handler.handle(message(task, taskMsg, "SUCCESS", "ok"));
        List<MassMessage> secondResponses = handler.handle(message(task, taskMsg, "FAILED", "boom"));

        assertEquals(200, gson.fromJson(firstResponses.get(0).getPayload(), MessageAckPayload.class).getCode());
        assertEquals(200, gson.fromJson(secondResponses.get(0).getPayload(), MessageAckPayload.class).getCode());

        TaskMsg updated = taskManager.getTaskMessage(task.getTid(), taskMsg.getMsgId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok", updated.getResult());
        assertNull(updated.getErrorMessage());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    private Task createRunningTask(String taskName) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setRoutingCode("us");
        dto.setSharedConfig(java.util.Map.of("textContent", "hello"));
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

        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-" + taskMsg.getMsgId() + "-1",
                task.getTid(), taskMsg.getMsgId(), 1);
        attempt.setWorkerId("worker-1");
        attempt.setWorkerContextId("worker-context-1");
        attempt.setBatchId("batch-0");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskManager.addTaskMessageAttempt(task.getTid(), taskMsg.getMsgId(), attempt);
        return task;
    }

    private MassMessage message(Task task, TaskMsg taskMsg, String status, String detail) {
        return message(task, taskMsg, status, detail, null);
    }

    private MassMessage message(Task task, TaskMsg taskMsg, String status, String detail, String errorCode) {
        MassMessage msg = new MassMessage();
        msg.setMsgId(taskMsg.getMsgId());
        msg.setMsgType(MessageType.TASK);
        msg.setSubMsgType("step");
        msg.setFrom(MessageDirection.CLIENT);
        msg.setProject(task.getProject());
        MessageContext context = new MessageContext();
        context.setTaskId(task.getTid());
        context.setWorkerId("worker-1");
        context.setConnRole(GatewayTaskMsgPublisher.DEFAULT_CONN_ROLE);
        msg.setContext(context);
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mockData", detail);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        msg.setPayload(gson.toJsonTree(payload));
        return msg;
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
