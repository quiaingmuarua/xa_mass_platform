package com.xa.mass.engine;

import java.util.function.Supplier;

/**
 * Pluggable concurrency strategy for task-level and message-level locking, and
 * for coalesced task-progress reconciliation.
 *
 * <p>The default {@link LocalTaskConcurrencyCoordinator} implements this contract
 * using JVM-local {@link java.util.concurrent.locks.ReentrantReadWriteLock}s.
 * Replace with a distributed implementation (e.g. Redisson, ZooKeeper) when the
 * engine runs across multiple nodes.
 */
interface TaskConcurrencyStrategy {

    <T> T withTaskWriteLock(String taskId, Supplier<T> action);

    <T> T withTaskReadLock(String taskId, Supplier<T> action);

    <T> T withTaskMessageReadLock(String taskId, String messageId, Supplier<T> action);

    void reconcileTaskProgress(String taskId, Runnable reconcileAction);
}
