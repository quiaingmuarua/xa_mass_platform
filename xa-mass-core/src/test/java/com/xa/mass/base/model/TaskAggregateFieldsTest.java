package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskAggregateFieldsTest {

    @Test
    void successCountIsTheCanonicalAggregateField() {
        Task task = new Task("task-1", "aggregate", "demoApp", 3, java.util.Map.of("textContent", "hello"), UserRef.of("user-1"));

        task.setTaskSuccessNumber(1);

        assertEquals(1, task.getTaskSuccessNumber());
        assertEquals(2, task.getTaskNonSuccessNumber());
    }

    @Test
    void updatingEligibleCountRecomputesNonSuccessCount() {
        Task task = new Task("task-2", "aggregate", "demoApp", 2, java.util.Map.of("textContent", "hello"), UserRef.of("user-2"));
        task.setTaskSuccessNumber(1);

        task.setTaskEligibleNumber(4);

        assertEquals(1, task.getTaskSuccessNumber());
        assertEquals(3, task.getTaskNonSuccessNumber());
    }

    @Test
    void nonSuccessCountSetterRecomputesDerivedValueInsteadOfOverridingIt() {
        Task task = new Task("task-3", "aggregate", "demoApp", 2, java.util.Map.of("textContent", "hello"), UserRef.of("user-3"));

        task.setTaskSuccessNumber(2);
        task.setTaskNonSuccessNumber(99);

        assertEquals(2, task.getTaskSuccessNumber());
        assertEquals(0, task.getTaskNonSuccessNumber());
    }

    @Test
    void openEndedCompatibilityProjectionTracksIntakeStatus() {
        Task task = new Task("task-4", "aggregate", "demoApp", 1, java.util.Map.of("textContent", "hello"), UserRef.of("user-4"));

        assertEquals(com.xa.mass.base.enums.task.TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertFalse(task.isOpenEnded());

        task.setOpenEnded(true);

        assertEquals(com.xa.mass.base.enums.task.TaskIntakeStatus.OPEN, task.getIntakeStatus());
        assertTrue(task.isOpenEnded());

        task.setIntakeStatus(com.xa.mass.base.enums.task.TaskIntakeStatus.SEALED);

        assertEquals(com.xa.mass.base.enums.task.TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertFalse(task.isOpenEnded());
    }

    @Test
    void batchSizeIsNormalizedAtTheSetterBoundary() {
        Task task = new Task("task-5", "aggregate", "demoApp", 1, java.util.Map.of("textContent", "hello"), UserRef.of("user-5"));

        task.setBatchSize(0);
        assertEquals(1, task.getBatchSize());

        task.setBatchSize(3);
        assertEquals(3, task.getBatchSize());
    }

    @Test
    void projectAndUserBindingsAreCanonicalizedOnTask() {
        Task task = new Task("task-6", "aggregate", "demoApp", 1, java.util.Map.of(), UserRef.of("agent-1"));

        assertEquals("demoApp", task.getProject());
        assertNotNull(task.getProjectRef());
        assertEquals("demoApp", task.getProjectRef().getCode());
        assertEquals("agent-1", task.getUser().getUserId());
    }
}
