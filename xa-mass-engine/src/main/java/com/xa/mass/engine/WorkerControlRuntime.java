package com.xa.mass.engine;

import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandDeliveryPort;
import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;
import com.xa.mass.worker.runtime.report.WorkerStateProjectionResult;
import com.xa.mass.worker.runtime.report.WorkerStateReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * External worker-control runtime surface for SDK and engine lifecycle callers.
 */
public interface WorkerControlRuntime {

    WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report);

    WorkerStateProjectionResult applyWorkerStateReport(WorkerStateReport report);

    WorkerCommandLifecycleResult requestWorkerCommand(WorkerCommandRequest request);

    WorkerCommandLifecycleResult applyWorkerCommandAcknowledgement(WorkerCommandAcknowledgement acknowledgement);

    List<WorkerCommandLifecycleResult> expireDueWorkerCommands(Instant now, int limit);

    List<WorkerCommandLifecycleResult> retryPendingWorkerCommandDeliveries(int limit, int maxAttempts);

    List<WorkerCommandRecord> claimPendingWorkerCommands(String workerId, int limit);

    Optional<WorkerCommandRecord> workerCommand(String commandId);

    List<WorkerCommandRecord> workerCommandsForWorker(String workerId);

    Optional<WorkerStateProjection> workerStateProjection(String workerId);

    List<WorkerStateProjection> workerStateProjections();

    void setDispatchWakeupCallback(Runnable dispatchWakeupCallback);

    void setCommandDeliveryPort(WorkerCommandDeliveryPort commandDeliveryPort, Executor commandDeliveryExecutor);
}
