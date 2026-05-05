package com.xa.mass.trace.api;

/**
 * All event types emitted by the trace sink.
 *
 * <p>Event types are grouped by the lifecycle they trace:</p>
 * <ul>
 *   <li><b>Task lifecycle</b>: {@link #TASK_STATUS_CHANGED}</li>
 *   <li><b>Message lifecycle</b>: {@link #MSG_STATUS_CHANGED}</li>
 *   <li><b>Dispatch</b>: {@link #MSG_DISPATCH_SENT}</li>
 *   <li><b>Retry</b>: {@link #MSG_RETRY_SCHEDULED}</li>
 *   <li><b>Lease</b>: {@link #LEASE_EXPIRED}</li>
 *   <li><b>Worker presence</b>: {@link #WORKER_ONLINE}, {@link #WORKER_OFFLINE}</li>
 * </ul>
 */
public enum ExecutionEventType {

    /**
     * A task moved from one status to another.
     * {@code src}/{@code dst} carry {@code TaskStatus} names.
     * {@code reason} is populated for terminal-state transitions.
     */
    TASK_STATUS_CHANGED,

    /**
     * A task message moved from one status to another.
     * {@code src}/{@code dst} carry {@code TaskMsgStatus} names.
     * {@code reason} is populated for FAILED and EXPIRED transitions.
     */
    MSG_STATUS_CHANGED,

    /**
     * A message was dispatched to a worker.
     * {@code workerId} and {@code adapterId} are required; {@code src}/{@code dst} are null.
     */
    MSG_DISPATCH_SENT,

    /**
     * A message was scheduled for retry.
     * {@code retryCount} is required.
     */
    MSG_RETRY_SCHEDULED,

    /**
     * A worker lease expired without a result being applied.
     * {@code workerId} and {@code messageId} are set; {@code src}/{@code dst} are null.
     */
    LEASE_EXPIRED,

    /**
     * A worker came online and registered with the platform.
     * {@code workerId} and {@code adapterId} are set.
     */
    WORKER_ONLINE,

    /**
     * A worker went offline or was deregistered.
     * {@code workerId}, {@code adapterId}, and {@code reason} are set.
     */
    WORKER_OFFLINE
}
