package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAggregateFieldsTest {

    @Test
    void successCountIsTheCanonicalAggregateField() {
        Task task = new Task("task-1", "aggregate", "demoApp", "us", 3, java.util.Map.of("textContent", "hello"), new User());

        task.setTaskSuccessNumber(1);

        assertEquals(1, task.getTaskSuccessNumber());
        assertEquals(2, task.getTaskNonSuccessNumber());
    }

    @Test
    void updatingEligibleCountRecomputesNonSuccessCount() {
        Task task = new Task("task-2", "aggregate", "demoApp", "us", 2, java.util.Map.of("textContent", "hello"), new User());
        task.setTaskSuccessNumber(1);

        task.setTaskEligibleNumber(4);

        assertEquals(1, task.getTaskSuccessNumber());
        assertEquals(3, task.getTaskNonSuccessNumber());
    }

    @Test
    void nonSuccessCountSetterRecomputesDerivedValueInsteadOfOverridingIt() {
        Task task = new Task("task-3", "aggregate", "demoApp", "us", 2, java.util.Map.of("textContent", "hello"), new User());

        task.setTaskSuccessNumber(2);
        task.setTaskNonSuccessNumber(99);

        assertEquals(2, task.getTaskSuccessNumber());
        assertEquals(0, task.getTaskNonSuccessNumber());
    }

    @Test
    void openEndedCompatibilityProjectionTracksIntakeStatus() {
        Task task = new Task("task-4", "aggregate", "demoApp", "us", 1, java.util.Map.of("textContent", "hello"), new User());

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
        Task task = new Task("task-5", "aggregate", "demoApp", "us", 1, java.util.Map.of("textContent", "hello"), new User());

        task.setBatchSize(0);
        assertEquals(1, task.getBatchSize());

        task.setBatchSize(3);
        assertEquals(3, task.getBatchSize());
    }
}
