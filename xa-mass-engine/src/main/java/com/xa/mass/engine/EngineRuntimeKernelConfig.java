package com.xa.mass.engine;

import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;

/**
 * Engine-owned assembly contract for the default runtime scheduling kernel.
 *
 * <p>The SDK may provide these dependencies, but listener/watchdog/strategy
 * implementation classes stay assembled inside the engine module.</p>
 */
public interface EngineRuntimeKernelConfig {

    TaskCommandService getTaskCommandService();

    TaskRuntimeRecoveryPort getTaskRuntimeRecoveryPort();

    TaskLeaseMaintenancePort getTaskLeaseMaintenancePort();

    TaskDispatchWakeupPort getTaskDispatchWakeupPort();

    TaskShellLifecycleMaintenancePort getTaskShellLifecycleMaintenancePort();

    TaskAssignmentRuntimePort getTaskAssignmentRuntimePort();

    TaskEventService getTaskEventService();

    WorkerAvailabilityWakeupRuntime getWorkerAvailabilityWakeupRuntime();

    WorkerSelectionRuntime getWorkerSelectionRuntime();

    WorkerControlRuntime getWorkerControlRuntime();

    AssignmentDiagnosticRecorder getRecordService();

    TraceEventLogger getTraceEventLogger();

    long getTaskMessageLeaseSeconds();

    long getAssignmentRetryDelayMillis();

    long getRuntimeReadyDispatchIntervalMillis();

    long getRuntimeReadyDispatchIdleBackoffMaxMillis();

    PollingIdleBackoffPolicy getRuntimeReadyDispatchIdleBackoffPolicy();

    long getLeaseWatchdogIntervalSeconds();

    long getWorkerCommandMaintenanceIntervalSeconds();

    int getWorkerCommandMaintenanceScanLimit();

    int getWorkerCommandDeliveryMaxAttempts();
}
