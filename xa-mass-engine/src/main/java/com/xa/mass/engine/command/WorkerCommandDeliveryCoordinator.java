package com.xa.mass.engine.command;

import com.xa.mass.engine.util.TraceEventLogger;

import java.util.Objects;

/**
 * Coordinates command delivery attempts with command lifecycle truth.
 *
 * <p>The coordinator owns only command-specific handoff semantics. It does not
 * use task dispatch, task result convergence, or transport-owned lifecycle
 * state.</p>
 */
public final class WorkerCommandDeliveryCoordinator {

    private final WorkerCommandLifecycleOwner lifecycleOwner;
    private final WorkerCommandDeliveryPort deliveryPort;
    private final TraceEventLogger traceEventLogger;

    public WorkerCommandDeliveryCoordinator(WorkerCommandLifecycleOwner lifecycleOwner,
                                            WorkerCommandDeliveryPort deliveryPort,
                                            TraceEventLogger traceEventLogger) {
        this.lifecycleOwner = Objects.requireNonNull(lifecycleOwner, "lifecycleOwner");
        this.deliveryPort = Objects.requireNonNull(deliveryPort, "deliveryPort");
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public WorkerCommandLifecycleResult deliver(String commandId) {
        WorkerCommandRecord record = lifecycleOwner.command(commandId).orElse(null);
        if (record == null) {
            return lifecycleOwner.transition(commandId, WorkerCommandStatus.DELIVERY_ACCEPTED,
                    "command not found for delivery");
        }
        if (record.status() != WorkerCommandStatus.REQUESTED) {
            WorkerCommandLifecycleResult lifecycleResult = lifecycleOwner.markDeliveryAccepted(
                    record.commandId(), "command delivery already applied");
            traceEventLogger.workerCommandStatusTransition(lifecycleResult);
            return lifecycleResult;
        }

        WorkerCommandDeliveryResult deliveryResult;
        try {
            deliveryResult = deliveryPort.deliver(record);
        } catch (RuntimeException e) {
            deliveryResult = WorkerCommandDeliveryResult.failed("command delivery failed: " + e.getMessage());
        }
        if (deliveryResult == null) {
            deliveryResult = WorkerCommandDeliveryResult.failed("command delivery returned no result");
        }
        WorkerCommandLifecycleResult lifecycleResult;
        if (deliveryResult.accepted()) {
            lifecycleResult = lifecycleOwner.markDeliveryAccepted(record.commandId(), deliveryResult.reason());
        } else {
            lifecycleResult = lifecycleOwner.markFailed(record.commandId(), deliveryResult.reason());
        }
        traceEventLogger.workerCommandStatusTransition(lifecycleResult);
        return lifecycleResult;
    }
}
