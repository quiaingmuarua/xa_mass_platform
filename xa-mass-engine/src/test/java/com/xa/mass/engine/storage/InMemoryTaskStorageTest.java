package com.xa.mass.engine.storage;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.model.TaskMsgAttempt;
import org.junit.jupiter.api.Test;

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
