package com.xa.mass.base.model;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLifecycleModelTest {

    @Test
    void transitionToBlockedRequiresHoldReasonAndSetsItAtomically() {
        Task task = new Task();

        assertThrows(IllegalArgumentException.class, () -> task.transitionToBlocked(null));

        assertTrue(task.transitionTo(TaskStatus.READY));
        assertTrue(task.transitionToBlocked(TaskHoldReason.MANUAL_BLOCKED));
        assertEquals(TaskStatus.BLOCKED, task.getStatus());
        assertEquals(TaskHoldReason.MANUAL_BLOCKED, task.getHoldReason());
    }

    @Test
    void cancelBeforeDispatchFinalizesOnlyInitMessages() {
        TaskMsg initMessage = new TaskMsg("msg-init", "task-1", java.util.Map.of("target", "alpha"));
        assertTrue(initMessage.cancelBeforeDispatch("task cancelled"));
        assertEquals(TaskMsgStatus.FAILED, initMessage.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, initMessage.getFinalReason());
        assertEquals("task cancelled", initMessage.getErrorMessage());

        TaskMsg assignedMessage = new TaskMsg("msg-assigned", "task-1", java.util.Map.of("target", "beta"));
        assertTrue(assignedMessage.markAsAssigned());
        assertFalse(assignedMessage.cancelBeforeDispatch("task cancelled"));
        assertEquals(TaskMsgStatus.ASSIGNED, assignedMessage.getStatus());
    }
}
