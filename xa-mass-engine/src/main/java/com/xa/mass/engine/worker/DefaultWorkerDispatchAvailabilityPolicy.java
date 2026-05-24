package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;

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
                                           WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
        if (projection == null) {
            return;
        }
        String state = projection.state();
        if (state == null || state.isBlank()) {
            return;
        }
        String normalizedState = state.trim().toUpperCase(Locale.ROOT);
        if ("DRAINING".equals(normalizedState)) {
            dispatchAvailabilityOwner.disableForDraining(projection.workerId(), WORKER_STATE, projection.reason());
            return;
        }
        if ("AVAILABLE".equals(normalizedState)) {
            dispatchAvailabilityOwner.clearSource(projection.workerId(), WORKER_STATE, projection.reason());
        }
    }

    @Override
    public void applyWorkerCommandLifecycleResult(WorkerCommandLifecycleResult result,
                                                  WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
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
                    dispatchAvailabilityOwner.disableForDraining(record.workerId(), WORKER_COMMAND, record.statusReason());
            default -> {
            }
        }
    }
}
