package com.xa.mass.engine.control;

import com.xa.mass.worker.runtime.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandDeliveryCoordinator;
import com.xa.mass.worker.runtime.command.WorkerCommandDeliveryPort;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResultCode;
import com.xa.mass.worker.runtime.command.WorkerCommandRecord;
import com.xa.mass.worker.runtime.command.WorkerCommandRequest;
import com.xa.mass.worker.runtime.command.WorkerCommandStatus;
import com.xa.mass.engine.WorkerControlRuntime;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.control.WorkerDispatchGateRuntime;
import com.xa.mass.worker.runtime.report.WorkerReportRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionResult;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateReport;
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
public final class WorkerControlService implements WorkerControlRuntime {
    private static final Logger log = LoggerFactory.getLogger(WorkerControlService.class);

    private final WorkerReportRuntime workerReportRuntime;
    private final WorkerResourceQueryRuntime workerResourceRuntime;
    private final WorkerDispatchGateRuntime dispatchGateRuntime;
    private final WorkerCommandLifecycleOwner commandLifecycleOwner;
    private final WorkerStateProjectionRuntime stateProjectionRuntime;
    private final WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy;
    private final TraceEventLogger traceEventLogger;
    private volatile Runnable dispatchWakeupCallback = () -> {
    };
    private volatile WorkerCommandDeliveryCoordinator commandDeliveryCoordinator;
    private volatile Executor commandDeliveryExecutor = Runnable::run;

    public WorkerControlService(WorkerReportRuntime workerReportRuntime,
                                WorkerResourceQueryRuntime workerResourceRuntime,
                                WorkerDispatchGateRuntime dispatchGateRuntime,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionRuntime stateProjectionRuntime,
                                WorkerDispatchAvailabilityPolicy dispatchAvailabilityPolicy,
                                TraceEventLogger traceEventLogger) {
        this.workerReportRuntime = Objects.requireNonNull(workerReportRuntime, "workerReportRuntime");
        this.workerResourceRuntime = Objects.requireNonNull(workerResourceRuntime, "workerResourceRuntime");
        this.dispatchGateRuntime = Objects.requireNonNull(dispatchGateRuntime, "dispatchGateRuntime");
        this.commandLifecycleOwner = Objects.requireNonNull(commandLifecycleOwner, "commandLifecycleOwner");
        this.stateProjectionRuntime = Objects.requireNonNull(stateProjectionRuntime, "stateProjectionRuntime");
        this.dispatchAvailabilityPolicy = dispatchAvailabilityPolicy != null
                ? dispatchAvailabilityPolicy
                : new DefaultWorkerDispatchAvailabilityPolicy();
        this.traceEventLogger = traceEventLogger != null ? traceEventLogger : TraceEventLogger.noop();
    }

    public WorkerControlService(WorkerReportRuntime workerReportRuntime,
                                WorkerResourceQueryRuntime workerResourceRuntime,
                                WorkerDispatchGateRuntime dispatchGateRuntime,
                                WorkerCommandLifecycleOwner commandLifecycleOwner,
                                WorkerStateProjectionRuntime stateProjectionRuntime,
                                TraceEventLogger traceEventLogger) {
        this(workerReportRuntime,
                workerResourceRuntime,
                dispatchGateRuntime,
                commandLifecycleOwner,
                stateProjectionRuntime,
                new DefaultWorkerDispatchAvailabilityPolicy(),
                traceEventLogger);
    }

    @Override
    public WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = workerReportRuntime.applyWorkerCapabilityReport(report);
        if (result.success() && result.snapshotChanged()) {
            notifyDispatchWakeup();
        }
        traceEventLogger.workerCapabilityReportApplied(result);
        return result;
    }

    @Override
    public WorkerStateProjectionResult applyWorkerStateReport(WorkerStateReport report) {
        WorkerStateProjectionResult result = stateProjectionRuntime.applyReport(report);
        if (result.success()) {
            dispatchAvailabilityPolicy.applyWorkerStateProjection(result.projection(), dispatchGateRuntime);
            if (isAvailableState(result.projection())
                    && workerResourceRuntime.worker(result.workerId()).isPresent()
                    && dispatchGateRuntime.isWorkerDispatchEnabled(result.workerId())) {
                notifyDispatchWakeup();
            }
        }
        traceEventLogger.workerStateReportApplied(result);
        return result;
    }

    @Override
    public WorkerCommandLifecycleResult requestWorkerCommand(WorkerCommandRequest request) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.requestCommand(request);
        traceEventLogger.workerCommandStatusTransition(result);
        if (result.code() == WorkerCommandLifecycleResultCode.ACCEPTED && result.record() != null) {
            enqueueCommandDelivery(result.record().commandId());
        }
        return result;
    }

    @Override
    public WorkerCommandLifecycleResult applyWorkerCommandAcknowledgement(WorkerCommandAcknowledgement acknowledgement) {
        WorkerCommandLifecycleResult result = commandLifecycleOwner.applyAcknowledgement(acknowledgement);
        if (result.success()) {
            dispatchAvailabilityPolicy.applyWorkerCommandLifecycleResult(result, dispatchGateRuntime);
        }
        traceEventLogger.workerCommandStatusTransition(result);
        return result;
    }

    @Override
    public List<WorkerCommandLifecycleResult> expireDueWorkerCommands(Instant now, int limit) {
        List<WorkerCommandLifecycleResult> results = commandLifecycleOwner.expireDueCommands(now, limit);
        for (WorkerCommandLifecycleResult result : results) {
            applyCommandLifecycleResultSideEffects(result, true);
        }
        return results;
    }

    @Override
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

    @Override
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

    @Override
    public Optional<WorkerCommandRecord> workerCommand(String commandId) {
        return commandLifecycleOwner.command(commandId);
    }

    @Override
    public List<WorkerCommandRecord> workerCommandsForWorker(String workerId) {
        return commandLifecycleOwner.commandsForWorker(workerId);
    }

    @Override
    public Optional<WorkerStateProjection> workerStateProjection(String workerId) {
        return stateProjectionRuntime.projection(workerId);
    }

    @Override
    public List<WorkerStateProjection> workerStateProjections() {
        return stateProjectionRuntime.projections();
    }

    @Override
    public void setDispatchWakeupCallback(Runnable dispatchWakeupCallback) {
        this.dispatchWakeupCallback = dispatchWakeupCallback != null ? dispatchWakeupCallback : () -> {
        };
    }

    public void setCommandDeliveryCoordinator(WorkerCommandDeliveryCoordinator commandDeliveryCoordinator,
                                              Executor commandDeliveryExecutor) {
        this.commandDeliveryCoordinator = commandDeliveryCoordinator;
        this.commandDeliveryExecutor = commandDeliveryExecutor != null ? commandDeliveryExecutor : Runnable::run;
    }

    @Override
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
            dispatchAvailabilityPolicy.applyWorkerCommandLifecycleResult(result, dispatchGateRuntime);
        }
        if (trace) {
            traceEventLogger.workerCommandStatusTransition(result);
        }
    }
}
