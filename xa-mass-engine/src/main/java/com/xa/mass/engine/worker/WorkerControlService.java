package com.xa.mass.engine.worker;

import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandDeliveryCoordinator;
import com.xa.mass.engine.command.WorkerCommandDeliveryPort;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandLifecycleResultCode;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.engine.command.WorkerCommandStatus;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

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
    private final WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy;
    private final TraceEventLogger traceEventLogger;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };
    private volatile WorkerCommandDeliveryCoordinator commandDeliveryCoordinator;
    private volatile Executor commandDeliveryExecutor = Runnable::run;

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy,
                                TraceEventLogger traceEventLogger) {
        this.workerManager = Objects.requireNonNull(workerManager, "workerManager");
        this.commandLifecycleOwner = Objects.requireNonNull(commandLifecycleOwner, "commandLifecycleOwner");
        this.stateProjectionOwner = Objects.requireNonNull(stateProjectionOwner, "stateProjectionOwner");
        this.dispatchAvailabilityPolicy = dispatchAvailabilityPolicy != null
                ? dispatchAvailabilityPolicy
                : new DefaultWorkerDispatchAvailabilityPolicy();
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public WorkerControlService(WorkerManager workerManager,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionOwner stateProjectionOwner,
                                TraceEventLogger traceEventLogger) {
        this(workerManager,
                commandLifecycleOwner,
                stateProjectionOwner,
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
            dispatchAvailabilityPolicy.applyWorkerStateProjection(result.projection(), workerManager);
            if (isAvailableState(result.projection())
                    && workerManager.getWorker(result.workerId()) != null
                    && workerManager.isWorkerDispatchEnabled(result.workerId())) {
                notifyDispatchWakeup();
            }
        }
        traceEventLogger.workerStateReportApplied(result);
        return result;
    }

    public WorkerCommandLifecycleResult requestWorkerCommand(WorkerCommandRequest request) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.requestCommand(request);
        traceEventLogger.workerCommandStatusTransition(result);
        if (result.code() == WorkerCommandLifecycleResultCode.ACCEPTED && result.record() != null) {
            enqueueCommandDelivery(result.record().commandId());
        }
        return result;
    }

    public WorkerCommandLifecycleResult applyWorkerCommandAcknowledgement(WorkerCommandAcknowledgement acknowledgement) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.applyAcknowledgement(acknowledgement);
        if (result.success()) {
            dispatchAvailabilityPolicy.applyWorkerCommandLifecycleResult(result, workerManager);
        }
        traceEventLogger.workerCommandStatusTransition(result);
        return result;
    }

    public List<WorkerCommandLifecycleResult> expireDueWorkerCommands(Instant now, int limit) {
        List<WorkerCommandLifecycleResult> results = commandLifecycleOwner.expireDueCommands(now, limit);
        for (WorkerCommandLifecycleResult result : results) {
            applyCommandLifecycleResultSideEffects(result, true);
        }
        return results;
    }

    public List<WorkerCommandLifecycleResult> retryPendingWorkerCommandDeliveries(int limit, int maxAttempts) {
        if (limit <= 0 || commandDeliveryCoordinator == null) {
            return List.of();
        }
        int boundedMaxAttempts = Math.max(1, maxAttempts);
        return commandLifecycleOwner.commandsByStatus(WorkerCommandStatus.REQUESTED, limit)
                .stream()
                .map(record -> retryPendingWorkerCommandDelivery(record, boundedMaxAttempts))
                .filter(Objects::nonNull)
                .toList();
    }

    public List<WorkerCommandRecord> claimPendingWorkerCommands(String workerId, int limit) {
        List<WorkerCommandLifecycleResult> results = commandLifecycleOwner.claimPendingCommandsForWorker(
                workerId,
                limit,
                "command pulled by worker"
        );
        for (WorkerCommandLifecycleResult result : results) {
            applyCommandLifecycleResultSideEffects(result, true);
        }
        return results.stream()
                .map(WorkerCommandLifecycleResult::record)
                .filter(Objects::nonNull)
                .toList();
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

    public void setCommandDeliveryCoordinator(WorkerCommandDeliveryCoordinator commandDeliveryCoordinator,
                                              Executor commandDeliveryExecutor) {
        this.commandDeliveryCoordinator = commandDeliveryCoordinator;
        this.commandDeliveryExecutor = commandDeliveryExecutor != null ? commandDeliveryExecutor : Runnable::run;
    }

    public void setCommandDeliveryPort(WorkerCommandDeliveryPort commandDeliveryPort,
                                       Executor commandDeliveryExecutor) {
        this.commandDeliveryCoordinator = commandDeliveryPort == null
                ? null
                : new WorkerCommandDeliveryCoordinator(commandLifecycleOwner, commandDeliveryPort, traceEventLogger);
        this.commandDeliveryExecutor = commandDeliveryExecutor != null ? commandDeliveryExecutor : Runnable::run;
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

    private void enqueueCommandDelivery(String commandId) {
        WorkerCommandDeliveryCoordinator coordinator = commandDeliveryCoordinator;
        if (coordinator == null) {
            return;
        }
        try {
            commandDeliveryExecutor.execute(() -> deliverCommandNow(commandId));
        } catch (RuntimeException e) {
            log.warn("Worker command delivery handoff failed for command {}", commandId, e);
            WorkerCommandLifecycleResult result = commandLifecycleOwner.markFailed(
                    commandId,
                    "command delivery handoff failed: " + e.getMessage()
            );
            applyCommandLifecycleResultSideEffects(result, true);
        }
    }

    private WorkerCommandLifecycleResult retryPendingWorkerCommandDelivery(WorkerCommandRecord record,
                                                                          int maxAttempts) {
        if (record == null || record.status() != WorkerCommandStatus.REQUESTED) {
            return null;
        }
        if (record.deliveryAttemptCount() >= maxAttempts) {
            WorkerCommandLifecycleResult result = commandLifecycleOwner.markFailed(
                    record.commandId(),
                    "worker command delivery attempts exhausted"
            );
            applyCommandLifecycleResultSideEffects(result, true);
            return result;
        }
        return deliverCommandNow(record.commandId());
    }

    private WorkerCommandLifecycleResult deliverCommandNow(String commandId) {
        WorkerCommandDeliveryCoordinator coordinator = commandDeliveryCoordinator;
        if (coordinator == null) {
            return null;
        }
        WorkerCommandLifecycleResult result = coordinator.deliver(commandId);
        applyCommandLifecycleResultSideEffects(result, false);
        return result;
    }

    private void applyCommandLifecycleResultSideEffects(WorkerCommandLifecycleResult result, boolean trace) {
        if (result == null) {
            return;
        }
        if (result.success()) {
            dispatchAvailabilityPolicy.applyWorkerCommandLifecycleResult(result, workerManager);
        }
        if (trace) {
            traceEventLogger.workerCommandStatusTransition(result);
        }
    }
}
