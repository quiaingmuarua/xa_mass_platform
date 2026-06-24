package com.xa.mass.worker.runtime.control;

import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.command.WorkerCommandRecord;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;

import java.util.Locale;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;

/**
 * Default worker-runtime policy that keeps dispatch eligibility as worker
 * runtime truth instead of an engine-control state model.
 */
public final class DefaultWorkerDispatchAvailabilityPolicy implements WorkerDispatchEligibilityRuntime {
    private final WorkerDispatchGateRuntime dispatchGateRuntime;
    private final WorkerDispatchRecoveryRuntime dispatchRecoveryRuntime;

    public DefaultWorkerDispatchAvailabilityPolicy(WorkerDispatchGateRuntime dispatchGateRuntime,
                                                   WorkerDispatchRecoveryRuntime dispatchRecoveryRuntime) {
        this.dispatchGateRuntime = java.util.Objects.requireNonNull(dispatchGateRuntime, "dispatchGateRuntime");
        this.dispatchRecoveryRuntime = java.util.Objects.requireNonNull(dispatchRecoveryRuntime,
                "dispatchRecoveryRuntime");
    }

    @Override
    public boolean isWorkerDispatchEnabled(String workerId) {
        return dispatchGateRuntime.isWorkerDispatchEnabled(workerId);
    }

    @Override
    public void applyWorkerStateProjection(WorkerStateProjection projection) {
        if (projection == null) {
            return;
        }
        String state = projection.state();
        if (state == null || state.isBlank()) {
            return;
        }
        String normalizedState = state.trim().toUpperCase(Locale.ROOT);
        if ("DRAINING".equals(normalizedState)) {
            dispatchGateRuntime.disableWorkerDispatch(projection.workerId(), WORKER_STATE, projection.reason());
            return;
        }
        if ("AVAILABLE".equals(normalizedState)) {
            dispatchRecoveryRuntime.recoverWorkerDispatch(projection.workerId(), WORKER_STATE, projection.reason());
        }
    }

    @Override
    public void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result) {
        WorkerCommandRecord record = result != null ? result.record() : null;
        if (record == null) {
            return;
        }
        String commandType = record.commandType();
        if (commandType == null || !"DRAIN".equalsIgnoreCase(commandType.trim())) {
            return;
        }
        if (result.currentStatus() == null) {
            return;
        }
        switch (result.currentStatus()) {
            case DELIVERY_ACCEPTED, EXECUTION_ACCEPTED, SUCCEEDED ->
                    dispatchGateRuntime.disableWorkerDispatch(record.workerId(), WORKER_COMMAND, record.statusReason());
            default -> {
            }
        }
    }
}
