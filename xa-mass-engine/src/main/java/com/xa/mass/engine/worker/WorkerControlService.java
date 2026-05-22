package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.engine.util.TraceEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
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
    private static final Logger log = LoggerFactory.getLogger(WorkerControlService.class);

    private final WorkerManager workerManager;
    private final WorkerCommandLifecycleOwner commandLifecycleOwner;
    private final WorkerStateProjectionOwner stateProjectionOwner;
    private final WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner;
    private final WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy;
    private final TraceEventLogger traceEventLogger;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner,
                                WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy,
                                TraceEventLogger traceEventLogger) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.commandLifecycleOwner = Objects.requireNonNull(commandLifecycleOwner, "commandLifecycleOwner");
        this.stateProjectionOwner = Objects.requireNonNull(stateProjectionOwner, "stateProjectionOwner");
        this.dispatchAvailabilityOwner = Objects.requireNonNull(dispatchAvailabilityOwner, "dispatchAvailabilityOwner");
        this.dispatchAvailabilityPolicy = dispatchAvailabilityPolicy != null
                ? dispatchAvailabilityPolicy
                : new DefaultWorkerDispatchAvailabilityPolicy();
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner,
                                TraceEventLogger traceEventLogger) {
        this(workerManager,
                commandLifecycleOwner,
                stateProjectionOwner,
                dispatchAvailabilityOwner,
                new DefaultWorkerDispatchAvailabilityPolicy(),
                traceEventLogger);
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
                new DefaultWorkerDispatchAvailabilityPolicy(),
                traceEventLogger);
    }

    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = workerManager.applyWorkerCapabilityReport(report);
        if (result.success() && result.snapshotChanged()) {
            notifyDispatchWakeup();
        }
        traceEventLogger.workerCapabilityReportApplied(result);
        return result;
    }

    public WorkerStateProjectionResult applyWorkerStateReport(WorkerStateReport report) {
        WorkerStateProjectionResult result = stateProjectionOwner.applyReport(report);
        if (result.success()) {
            dispatchAvailabilityPolicy.applyWorkerStateProjection(result.projection(), dispatchAvailabilityOwner);
            if (isAvailableState(result.projection())
                    && workerManager.getWorker(result.workerId()) != null
                    && dispatchAvailabilityOwner.isDispatchEnabled(result.workerId())) {
                notifyDispatchWakeup();
            }
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
            dispatchAvailabilityPolicy.applyWorkerCommandLifecycleResult(result, dispatchAvailabilityOwner);
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

    public void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    private boolean isAvailableState(WorkerStateProjection projection) {
        if (projection == null || projection.state() == null) {
            return false;
        }
        return "AVAILABLE".equals(projection.state().trim().toUpperCase(Locale.ROOT));
    }

    private void notifyDispatchWakeup() {
        try {
            dispatchWakeupCallback.run();
        } catch (RuntimeException e) {
            log.warn("Worker dispatch wakeup callback failed", e);
        }
    }
}
