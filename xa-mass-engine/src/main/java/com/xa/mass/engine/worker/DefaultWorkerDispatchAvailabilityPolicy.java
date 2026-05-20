package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;

import java.util.Locale;

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
            dispatchAvailabilityOwner.disableForDraining(projection.workerId(), projection.reason());
            return;
        }
        if ("AVAILABLE".equals(normalizedState)) {
            dispatchAvailabilityOwner.enable(projection.workerId(), projection.reason());
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
                    dispatchAvailabilityOwner.disableForDraining(record.workerId(), record.statusReason());
            default -> {
            }
        }
    }
}
