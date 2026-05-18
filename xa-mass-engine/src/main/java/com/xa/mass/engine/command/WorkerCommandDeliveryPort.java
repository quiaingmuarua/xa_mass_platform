package com.xa.mass.engine.command;

/**
 * Command-specific delivery handoff port.
 *
 * <p>This is a protocol seam between the command lifecycle owner and a future
 * delivery implementation. It is intentionally not the task dispatch channel
 * and does not own command lifecycle state.</p>
 */
public interface WorkerCommandDeliveryPort {

    WorkerCommandDeliveryResult deliver(WorkerCommandRecord command);
}
