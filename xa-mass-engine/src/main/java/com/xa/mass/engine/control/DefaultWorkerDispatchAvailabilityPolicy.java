package com.xa.mass.engine.control;

import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.command.WorkerCommandRecord;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;

import java.util.Locale;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;

/**
 * Current default policy that translates bounded worker control state into the
 * worker dispatch gate truth consumed by scheduling.
 */
public final class DefaultWorkerDispatchAvailabilityPolicy implements WorkerDispatchAvailabilityPolicy {

    @Override
    public void applyWorkerStateProjection(WorkerStateProjection projection,
                                           WorkerDispatchGateRuntime dispatchGateRuntime) {
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
            dispatchGateRuntime.clearWorkerDispatchDisable(projection.workerId(), WORKER_STATE, projection.reason());
        }
    }

    @Override
    public void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result,
                                                  WorkerDispatchGateRuntime dispatchGateRuntime) {
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
