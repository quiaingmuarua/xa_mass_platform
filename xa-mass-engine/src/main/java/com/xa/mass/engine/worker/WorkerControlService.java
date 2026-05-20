package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.engine.util.TraceEventLogger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-backed worker-control entry and read surface for engine callers.
 *
 * <p>This service is the direct caller surface for SDK/server/runtime shells.
 * It delegates lifecycle truth to concrete owners and owns only the stable
 * entry/read handoff plus canonical trace emission. Event handlers should parse
 * event payloads and call this service instead of owning mutation themselves.</p>
 */
public final class WorkerControlService {

    private final WorkerManager workerManager;
    private final WorkerCommandLifecycleOwner commandLifecycleOwner;
    private final WorkerStateProjectionOwner stateProjectionOwner;
    private final WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner;
    private final TraceEventLogger traceEventLogger;

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner,
                                TraceEventLogger traceEventLogger) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.commandLifecycleOwner = Objects.requireNonNull(commandLifecycleOwner, "commandLifecycleOwner");
        this.stateProjectionOwner = Objects.requireNonNull(stateProjectionOwner, "stateProjectionOwner");
        this.dispatchAvailabilityOwner = Objects.requireNonNull(dispatchAvailabilityOwner, "dispatchAvailabilityOwner");
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                TraceEventLogger traceEventLogger) {
        this(workerManager,
                commandLifecycleOwner,
                stateProjectionOwner,
                workerManager != null
                        ? workerManager.getDispatchAvailabilityOwner()
                        : new WorkerDispatchAvailabilityOwner(),
                traceEventLogger);
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = workerManager.applyWorkerCapabilityReport(report);
        traceEventLogger.workerCapabilityReportApplied(result);
        return result;
    }

    public WorkerStateProjectionResult applyWorkerStateReport(WorkerStateReport report) {
        WorkerStateProjectionResult result = stateProjectionOwner.applyReport(report);
        if (result.success()) {
            applyDispatchAvailability(result.projection());
        }
        traceEventLogger.workerStateReportApplied(result);
        return result;
    }

    public WorkerCommandLifecycleResult requestWorkerCommand(WorkerCommandRequest request) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.requestCommand(request);
        traceEventLogger.workerCommandStatusTransition(result);
        return result;
    }

    public WorkerCommandLifecycleResult applyWorkerCommandAcknowledgement(WorkerCommandAcknowledgement acknowledgement) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.applyAcknowledgement(acknowledgement);
        if (result.success()) {
            applyCommandDispatchAvailability(result);
        }
        traceEventLogger.workerCommandStatusTransition(result);
        return result;
    }

    public Optional<WorkerCommandRecord> workerCommand(String commandId) {
        return commandLifecycleOwner.command(commandId);
    }

    public List<WorkerCommandRecord> workerCommandsForWorker(String workerId) {
        return commandLifecycleOwner.commandsForWorker(workerId);
    }

    public Optional<WorkerStateProjection> workerStateProjection(String workerId) {
        return stateProjectionOwner.projection(workerId);
    }

    public List<WorkerStateProjection> workerStateProjections() {
        return stateProjectionOwner.projections();
    }

    private void applyDispatchAvailability(WorkerStateProjection projection) {
        if (projection == null) {
            return;
        }
        String state = projection.state();
        if (state == null || state.isBlank()) {
            return;
        }
        String normalizedState = state.trim().toUpperCase(java.util.Locale.ROOT);
        if ("DRAINING".equals(normalizedState)) {
            dispatchAvailabilityOwner.disableForDraining(projection.workerId(), projection.reason());
            return;
        }
        if ("AVAILABLE".equals(normalizedState)) {
            dispatchAvailabilityOwner.enable(projection.workerId(), projection.reason());
        }
    }

    private void applyCommandDispatchAvailability(WorkerCommandLifecycleResult result) {
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
