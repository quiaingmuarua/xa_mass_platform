package com.xa.mass.base.enums.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStatusTest {

    @Test
    void newStateTransitions() {
        assertTrue(TaskStatus.NEW.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.NEW.canTransitionTo(TaskStatus.READY));
        assertTrue(TaskStatus.NEW.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.NEW.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.NEW.canTransitionTo(TaskStatus.PAUSED));
    }

    @Test
    void blockedStateTransitions() {
        assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.READY));
        assertTrue(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.BLOCKED.canTransitionTo(TaskStatus.PAUSED));
    }

    @Test
    void readyStateTransitions() {
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.RUNNING));
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.PAUSED));
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.READY.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.READY.canTransitionTo(TaskStatus.NEW));
    }

    @Test
    void runningStateTransitions() {
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.BLOCKED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PAUSED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.READY));
    }

    @Test
    void pausedStateTransitions() {
        assertTrue(TaskStatus.PAUSED.canTransitionTo(TaskStatus.READY));
        assertTrue(TaskStatus.PAUSED.canTransitionTo(TaskStatus.TERMINAL));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.BLOCKED));
        assertFalse(TaskStatus.PAUSED.canTransitionTo(TaskStatus.RUNNING));
    }

    @Test
    void terminalStateTransitions() {
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.NEW));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.BLOCKED));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.READY));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.RUNNING));
        assertFalse(TaskStatus.TERMINAL.canTransitionTo(TaskStatus.PAUSED));
    }

    @Test
    void stateProperties() {
        assertTrue(TaskStatus.TERMINAL.isFinal());
        assertFalse(TaskStatus.NEW.isFinal());
        assertFalse(TaskStatus.BLOCKED.isFinal());
        assertFalse(TaskStatus.READY.isFinal());
        assertFalse(TaskStatus.RUNNING.isFinal());
        assertFalse(TaskStatus.PAUSED.isFinal());

        assertTrue(TaskStatus.READY.isSchedulable());
        assertFalse(TaskStatus.NEW.isSchedulable());
        assertFalse(TaskStatus.BLOCKED.isSchedulable());
        assertFalse(TaskStatus.RUNNING.isSchedulable());
        assertFalse(TaskStatus.PAUSED.isSchedulable());
        assertFalse(TaskStatus.TERMINAL.isSchedulable());

        assertTrue(TaskStatus.RUNNING.isRunning());
        assertFalse(TaskStatus.NEW.isRunning());
        assertFalse(TaskStatus.BLOCKED.isRunning());
        assertFalse(TaskStatus.READY.isRunning());
        assertFalse(TaskStatus.PAUSED.isRunning());
        assertFalse(TaskStatus.TERMINAL.isRunning());

        assertTrue(TaskStatus.BLOCKED.isBlocked());
        assertFalse(TaskStatus.NEW.isBlocked());
        assertFalse(TaskStatus.READY.isBlocked());
        assertFalse(TaskStatus.RUNNING.isBlocked());
        assertFalse(TaskStatus.PAUSED.isBlocked());
        assertFalse(TaskStatus.TERMINAL.isBlocked());

        assertTrue(TaskStatus.PAUSED.isPaused());
        assertFalse(TaskStatus.NEW.isPaused());
        assertFalse(TaskStatus.BLOCKED.isPaused());
        assertFalse(TaskStatus.READY.isPaused());
        assertFalse(TaskStatus.RUNNING.isPaused());
        assertFalse(TaskStatus.TERMINAL.isPaused());

        assertTrue(TaskStatus.READY.isActive());
        assertTrue(TaskStatus.RUNNING.isActive());
        assertFalse(TaskStatus.NEW.isActive());
        assertFalse(TaskStatus.BLOCKED.isActive());
        assertFalse(TaskStatus.PAUSED.isActive());
        assertFalse(TaskStatus.TERMINAL.isActive());
    }

    @Test
    void stateDescriptionsAreReadable() {
        assertEquals("New", TaskStatus.NEW.getDescription());
        assertEquals("Blocked", TaskStatus.BLOCKED.getDescription());
        assertEquals("Ready", TaskStatus.READY.getDescription());
        assertEquals("Running", TaskStatus.RUNNING.getDescription());
        assertEquals("Paused", TaskStatus.PAUSED.getDescription());
        assertEquals("Terminal", TaskStatus.TERMINAL.getDescription());
    }
}
