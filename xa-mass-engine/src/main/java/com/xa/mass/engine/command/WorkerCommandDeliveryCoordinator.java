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
            WorkerCommandLifecycleResult lifecycleResult = new WorkerCommandLifecycleResult(
                    WorkerCommandLifecycleResultCode.IDEMPOTENT,
                    record,
                    record.status(),
                    record.status(),
                    "command delivery already applied"
            );
            traceEventLogger.workerCommandStatusTransition(lifecycleResult);
            return lifecycleResult;
        }

        WorkerCommandRecord attemptRecord = lifecycleOwner.beginDeliveryAttempt(
                record.commandId(),
                "command delivery attempt started"
        );
        if (attemptRecord == null || attemptRecord.status() != WorkerCommandStatus.REQUESTED) {
            WorkerCommandRecord latest = lifecycleOwner.command(record.commandId()).orElse(record);
            WorkerCommandLifecycleResult lifecycleResult = new WorkerCommandLifecycleResult(
                    WorkerCommandLifecycleResultCode.IDEMPOTENT,
                    latest,
                    latest.status(),
                    latest.status(),
                    "command delivery already in flight or applied"
            );
            traceEventLogger.workerCommandStatusTransition(lifecycleResult);
            return lifecycleResult;
        }

        try {
            WorkerCommandDeliveryResult deliveryResult;
            deliveryResult = deliveryPort.deliver(attemptRecord);
            if (deliveryResult == null) {
                deliveryResult = WorkerCommandDeliveryResult.failed("command delivery returned no result");
            }
            WorkerCommandLifecycleResult lifecycleResult;
            if (deliveryResult.accepted()) {
                lifecycleResult = lifecycleOwner.markDeliveryAccepted(attemptRecord.commandId(), deliveryResult.reason());
            } else if (deliveryResult.deferred() || deliveryResult.workerUnavailable()) {
                WorkerCommandRecord deferredRecord = lifecycleOwner.recordStatusReason(
                        attemptRecord.commandId(),
                        deliveryResult.reason()
                );
                lifecycleResult = new WorkerCommandLifecycleResult(
                        WorkerCommandLifecycleResultCode.DEFERRED,
                        deferredRecord != null ? deferredRecord : attemptRecord,
                        attemptRecord.status(),
                        attemptRecord.status(),
                        deliveryResult.reason()
                );
            } else {
                lifecycleResult = lifecycleOwner.markFailed(attemptRecord.commandId(), deliveryResult.reason());
            }
            traceEventLogger.workerCommandStatusTransition(lifecycleResult);
            return lifecycleResult;
        } catch (RuntimeException e) {
            WorkerCommandLifecycleResult lifecycleResult = lifecycleOwner.markFailed(
                    attemptRecord.commandId(),
                    "command delivery failed: " + e.getMessage()
            );
            traceEventLogger.workerCommandStatusTransition(lifecycleResult);
            return lifecycleResult;
        } finally {
            lifecycleOwner.completeDeliveryAttempt(attemptRecord.commandId());
        }
    }
}
