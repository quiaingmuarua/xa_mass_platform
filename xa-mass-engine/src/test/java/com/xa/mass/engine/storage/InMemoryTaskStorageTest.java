package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTaskStorageTest {

    @Test
    void latestActiveAttemptIgnoresNewerFinalAttempts() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();

        TaskMsgAttempt runningAttempt = attempt("attempt-1", 1, TaskMsgAttemptStatus.RUNNING);
        TaskMsgAttempt failedAttempt = attempt("attempt-2", 2, TaskMsgAttemptStatus.FAILED);

        storage.addTaskMessageAttempt("task-1", "msg-1", runningAttempt);
        storage.addTaskMessageAttempt("task-1", "msg-1", failedAttempt);

        Optional<TaskMsgAttempt> latestActive = storage.getLatestActiveTaskMessageAttempt("task-1", "msg-1");

        assertTrue(latestActive.isPresent());
        assertEquals("attempt-1", latestActive.get().getAttemptId());
    }

    @Test
    void getTaskMessagesPageReadsOnlyRequestedWindowInOrder() {
        InMemoryTaskStorage storage = new InMemoryTaskStorage();
        Task task = new Task();
        task.setTid("task-1");
        storage.saveTask(task);
        storage.addTaskMessage("task-1", new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "alpha")));
        storage.addTaskMessage("task-1", new TaskMsg("msg-2", "task-1", java.util.Map.of("target", "beta")));
        storage.addTaskMessage("task-1", new TaskMsg("msg-3", "task-1", java.util.Map.of("target", "gamma")));

        List<TaskMsg> page = storage.getTaskMessagesPage("task-1", 1, 1);

        assertEquals(3L, storage.countTaskMessages("task-1"));
        assertEquals(1, page.size());
        assertEquals("msg-2", page.get(0).getMessageId());
    }

    private TaskMsgAttempt attempt(String attemptId, int attemptNo, TaskMsgAttemptStatus status) {
        TaskMsgAttempt attempt = new TaskMsgAttempt();
        attempt.setAttemptId(attemptId);
        attempt.setAttemptNo(attemptNo);
        attempt.setTaskId("task-1");
        attempt.setMessageId("msg-1");
        attempt.setStatus(status);
        return attempt;
    }
}
