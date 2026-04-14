package com.xa.mass.base.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void nonSuccessCountCanStillBeExplicitlyOverriddenForAuditScenarios() {
        Task task = new Task("task-3", "aggregate", "demoApp", "us", 2, java.util.Map.of("textContent", "hello"), new User());

        task.setTaskSuccessNumber(2);
        task.setTaskNonSuccessNumber(0);

        assertEquals(2, task.getTaskSuccessNumber());
        assertEquals(0, task.getTaskNonSuccessNumber());
    }
}
