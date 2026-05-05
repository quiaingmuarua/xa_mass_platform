package com.xa.mass.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * JVM-local implementation of {@link TaskConcurrencyStrategy}.
 *
 * <p>Uses per-task {@link ReentrantReadWriteLock}s and coalesced condition-based
 * reconciliation, all scoped to this JVM instance. Replace with a distributed
 * implementation (e.g. Redisson, ZooKeeper) when the engine runs multi-node.
 */
final class LocalTaskConcurrencyCoordinator implements TaskConcurrencyStrategy {

    private final Map<String, TaskLockHandle> taskLocks = new ConcurrentHashMap<>();
    private final Map<String, MessageLockHandle> taskMessageLocks = new ConcurrentHashMap<>();
    private final Map<String, TaskProgressReconcileHandle> taskProgressReconcileHandles = new ConcurrentHashMap<>();

    @Override
    public <T> T withTaskWriteLock(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) {
            return action.get();
        }
        TaskLockHandle lockHandle = acquireTaskLockHandle(taskId);
        lockHandle.lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.writeLock().unlock();
            releaseTaskLockHandle(taskId, lockHandle);
        }
    }

    @Override
    public <T> T withTaskReadLock(String taskId, Supplier<T> action) {
        if (taskId == null || taskId.isBlank()) {
            return action.get();
        }
        TaskLockHandle lockHandle = acquireTaskLockHandle(taskId);
        lockHandle.lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.readLock().unlock();
            releaseTaskLockHandle(taskId, lockHandle);
        }
    }

    @Override
    public <T> T withTaskMessageReadLock(String taskId, String messageId, Supplier<T> action) {
        if (messageId == null || messageId.isBlank()) {
            return withTaskReadLock(taskId, action);
        }
        return withTaskReadLock(taskId, () -> withMessageLock(taskId, messageId, action));
    }

    @Override
    public void reconcileTaskProgress(String taskId, Runnable reconcileAction) {
        if (taskId == null || taskId.isBlank()) {
            withTaskWriteLock(taskId, () -> {
                reconcileAction.run();
                return null;
            });
            return;
        }

        TaskProgressReconcileHandle handle = acquireTaskProgressReconcileHandle(taskId);
        try {
            long requestedVersion;
            boolean leader;
            handle.lock.lock();
            try {
                requestedVersion = ++handle.requestedVersion;
                if (!handle.running) {
                    handle.running = true;
                    leader = true;
                } else {
                    leader = false;
                }
            } finally {
                handle.lock.unlock();
            }

            if (!leader) {
                awaitTaskProgressReconcile(handle, requestedVersion);
                return;
            }
            runTaskProgressReconcileLoop(taskId, handle, reconcileAction);
        } finally {
            releaseTaskProgressReconcileHandle(taskId, handle);
        }
    }

    private void awaitTaskProgressReconcile(TaskProgressReconcileHandle handle, long requestedVersion) {
        handle.lock.lock();
        try {
            while (handle.running && handle.completedVersion < requestedVersion) {
                handle.idle.awaitUninterruptibly();
            }
        } finally {
            handle.lock.unlock();
        }
    }

    private void runTaskProgressReconcileLoop(String taskId,
                                              TaskProgressReconcileHandle handle,
                                              Runnable reconcileAction) {
        try {
            while (true) {
                long targetVersion;
                handle.lock.lock();
                try {
                    targetVersion = handle.requestedVersion;
                } finally {
                    handle.lock.unlock();
                }

                withTaskWriteLock(taskId, () -> {
                    reconcileAction.run();
                    return null;
                });

                boolean done;
                handle.lock.lock();
                try {
                    handle.completedVersion = Math.max(handle.completedVersion, targetVersion);
                    done = handle.requestedVersion <= handle.completedVersion;
                    if (done) {
                        handle.running = false;
                    }
                    handle.idle.signalAll();
                } finally {
                    handle.lock.unlock();
                }
                if (done) {
                    return;
                }
            }
        } catch (RuntimeException | Error ex) {
            handle.lock.lock();
            try {
                handle.running = false;
                handle.idle.signalAll();
            } finally {
                handle.lock.unlock();
            }
            throw ex;
        }
    }

    private <T> T withMessageLock(String taskId, String messageId, Supplier<T> action) {
        String lockKey = taskId + "|" + messageId;
        MessageLockHandle lockHandle = acquireMessageLockHandle(lockKey);
        lockHandle.lock.lock();
        try {
            return action.get();
        } finally {
            lockHandle.lock.unlock();
            releaseMessageLockHandle(lockKey, lockHandle);
        }
    }

    private TaskLockHandle acquireTaskLockHandle(String taskId) {
        return taskLocks.compute(taskId, (ignored, existing) -> {
            TaskLockHandle handle = existing == null ? new TaskLockHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private void releaseTaskLockHandle(String taskId, TaskLockHandle lockHandle) {
        taskLocks.computeIfPresent(taskId, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && existing.lock.getReadLockCount() == 0
                    && !existing.lock.isWriteLocked()
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private MessageLockHandle acquireMessageLockHandle(String lockKey) {
        return taskMessageLocks.compute(lockKey, (ignored, existing) -> {
            MessageLockHandle handle = existing == null ? new MessageLockHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private void releaseMessageLockHandle(String lockKey, MessageLockHandle lockHandle) {
        taskMessageLocks.computeIfPresent(lockKey, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && !existing.lock.isLocked()
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private TaskProgressReconcileHandle acquireTaskProgressReconcileHandle(String taskId) {
        return taskProgressReconcileHandles.compute(taskId, (ignored, existing) -> {
            TaskProgressReconcileHandle handle = existing == null ? new TaskProgressReconcileHandle() : existing;
            handle.referenceCount++;
            return handle;
        });
    }

    private void releaseTaskProgressReconcileHandle(String taskId, TaskProgressReconcileHandle lockHandle) {
        taskProgressReconcileHandles.computeIfPresent(taskId, (ignored, existing) -> {
            if (existing != lockHandle) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount == 0
                    && !existing.running
                    && !existing.lock.hasQueuedThreads()) {
                return null;
            }
            return existing;
        });
    }

    private static final class TaskLockHandle {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private int referenceCount;
    }

    private static final class MessageLockHandle {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int referenceCount;
    }

    private static final class TaskProgressReconcileHandle {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition idle = lock.newCondition();
        private long requestedVersion;
        private long completedVersion;
        private boolean running;
        private int referenceCount;
    }
}
