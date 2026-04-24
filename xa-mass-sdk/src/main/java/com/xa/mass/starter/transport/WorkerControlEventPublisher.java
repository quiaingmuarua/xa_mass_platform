package com.xa.mass.starter.transport;

/**
 * Explicit transport-binding seam for outbound worker control/debug event
 * delivery.
 */
public interface WorkerControlEventPublisher {

    WorkerControlEventPublishResult publish(WorkerControlEventDispatch request);
}
