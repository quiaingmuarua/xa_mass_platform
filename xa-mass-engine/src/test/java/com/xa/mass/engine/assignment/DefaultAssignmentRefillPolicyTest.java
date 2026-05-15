package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAssignmentRefillPolicyTest {

    private final DefaultAssignmentRefillPolicy policy = new DefaultAssignmentRefillPolicy();

    @Test
    void runningTaskWithReadyWorkRequestsDispatch() {
        AssignmentRefillDecision decision = policy.decide(new AssignmentRefillRequest(
                task(TaskStatus.RUNNING),
                () -> true
        ));

        assertEquals(AssignmentRefillOutcome.REQUEST_DISPATCH, decision.outcome());
        assertTrue(decision.shouldRequestDispatch());
        assertTrue(decision.reason().contains("runtime-ready work"));
    }

    @Test
    void runningTaskWithoutReadyWorkSkipsRefill() {
        AssignmentRefillDecision decision = policy.decide(new AssignmentRefillRequest(
                task(TaskStatus.RUNNING),
                () -> false
        ));

        assertEquals(AssignmentRefillOutcome.SKIP, decision.outcome());
        assertFalse(decision.shouldRequestDispatch());
        assertTrue(decision.reason().contains("no runtime-ready work"));
    }

    @Test
    void nonRunningTaskSkipsWithoutReadingReadyWork() {
        AtomicBoolean readyWorkRead = new AtomicBoolean(false);

        AssignmentRefillDecision decision = policy.decide(new AssignmentRefillRequest(
                task(TaskStatus.TERMINAL),
                () -> {
                    readyWorkRead.set(true);
                    return true;
                }
        ));

        assertEquals(AssignmentRefillOutcome.SKIP, decision.outcome());
        assertFalse(readyWorkRead.get());
        assertTrue(decision.reason().contains("not running"));
    }

    private Task task(TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(status);
        return task;
    }
}
