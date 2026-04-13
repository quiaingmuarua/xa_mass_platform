package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.model.TaskCreateRequestDto;
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
        assertFalse(taskManager.resumeTask(task.getTid()));
    }

    @Test
    void createTaskWithNullTargetListDoesNotThrow() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("no-targets");
        dto.setProject("demoApp");
        dto.setCountryCode("us");
        dto.setTextContent("smoke");
        dto.setUserId("agent");
        dto.setTargetList(null); // previously caused NPE at line 80
        dto.setBatchSize(0);

        Task task = assertDoesNotThrow(() -> taskManager.createTask(dto));
        assertNotNull(task);
        assertTrue(taskManager.getTaskMessages(task.getTid()).isEmpty());
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
            msg.markAsSent();
            taskManager.updateTaskMessage(task.getTid(), msg);
        });

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMsgId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMsgId(), true, "done-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(2, updatedTask.getTaskExecutedNumber());
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
        message.markAsSent();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom"));

        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskMsgStatus.FAILED, updatedMessage.getStatus());
        assertEquals("boom", updatedMessage.getErrorMessage());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskExecutedNumber());
    }

    @Test
    void pausedTaskCompletesToTerminalWhenFinalResultArrives() {
        Task task = taskManager.createTask(buildRequest("task-paused-completion", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
        message.markAsSent();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-while-paused"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(1, updatedTask.getTaskExecutedNumber());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.transitionTo(TaskMsgStatus.BINDING);
        message.markAsSent();
        taskManager.updateTaskMessage(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), true, "done-once"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMsgId(), false, "boom-twice"));

        TaskMsg updatedMessage = taskManager.getTaskMessage(task.getTid(), message.getMsgId());
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals("done-once", updatedMessage.getResult());
        assertNull(updatedMessage.getErrorMessage());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(1, updatedTask.getTaskExecutedNumber());
        assertEquals(1, scheduler.completedTaskMsgCount);
        assertEquals(0, scheduler.failedTaskMsgCount);
    }

    private TaskCreateRequestDto buildRequest(String taskName) {
        return buildRequest(taskName, List.of("alpha", "beta"));
    }

    private TaskCreateRequestDto buildRequest(String taskName, List<String> targets) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setCountryCode("us");
        dto.setTextContent("smoke");
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
