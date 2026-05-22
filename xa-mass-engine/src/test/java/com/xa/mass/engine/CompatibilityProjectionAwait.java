package com.xa.mass.engine;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

import java.util.concurrent.TimeUnit;

final class CompatibilityProjectionAwait {

    private CompatibilityProjectionAwait() {
    }

    static TaskDetailStore.TaskMessageProjection awaitVisibleTaskMessageProjection(
            ProjectionAwareTaskManager manager,
            String taskId,
            String messageId,
            TaskMessageProjectionStatus expectedStatus) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TaskDetailStore.TaskMessageProjection lastSeen = null;
        while (System.nanoTime() < deadlineNanos) {
            lastSeen = manager.getVisibleTaskMessageProjection(taskId, messageId);
            if (lastSeen != null && lastSeen.status() == expectedStatus) {
                return lastSeen;
            }
            sleepBriefly();
        }
        return lastSeen;
    }

    static TaskDetailStore.TaskMessageAttemptProjection awaitVisibleTaskMessageAttemptProjection(
            ProjectionAwareTaskManager manager,
            String taskId,
            String messageId,
            TaskMessageAttemptProjectionStatus expectedStatus) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TaskDetailStore.TaskMessageAttemptProjection lastSeen = null;
        while (System.nanoTime() < deadlineNanos) {
            lastSeen = manager.getLatestTaskMessageAttemptAuditProjection(taskId, messageId);
            if (lastSeen != null && lastSeen.status() == expectedStatus) {
                return lastSeen;
            }
            sleepBriefly();
        }
        return lastSeen;
    }

    static TaskDetailStore.TaskMessageProjection awaitVisibleTaskMessageProjectionToLeaveStatus(
            ProjectionAwareTaskManager manager,
            String taskId,
            String messageId,
            TaskMessageProjectionStatus initialStatus) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TaskDetailStore.TaskMessageProjection lastSeen = null;
        while (System.nanoTime() < deadlineNanos) {
            lastSeen = manager.getVisibleTaskMessageProjection(taskId, messageId);
            if (lastSeen != null && lastSeen.status() != initialStatus) {
                return lastSeen;
            }
            sleepBriefly();
        }
        return lastSeen;
    }

    static TaskDetailStore.TaskMessageAttemptProjection awaitVisibleTaskMessageAttemptProjectionToBecomeFinal(
            ProjectionAwareTaskManager manager,
            String taskId,
            String messageId) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        TaskDetailStore.TaskMessageAttemptProjection lastSeen = null;
        while (System.nanoTime() < deadlineNanos) {
            lastSeen = manager.getLatestTaskMessageAttemptAuditProjection(taskId, messageId);
            if (lastSeen != null && lastSeen.status().isFinal()) {
                return lastSeen;
            }
            sleepBriefly();
        }
        return lastSeen;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
