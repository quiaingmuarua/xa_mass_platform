package com.xa.mass.engine;

import com.xa.mass.engine.rules.MatchingRuleEvaluator;
import com.xa.mass.engine.rules.MatchingRuleSetProvider;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAvailabilityWakeupRuntime;
import com.xa.mass.worker.runtime.admission.WorkerWarmHintRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.resource.WorkerResourceRuntime;

import java.util.Map;

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

    WorkerAdmissionRuntime getWorkerAdmissionRuntime();

    WorkerAvailabilityWakeupRuntime getWorkerAvailabilityWakeupRuntime();

    WorkerWarmHintRuntime getWorkerWarmHintRuntime();

    WorkerCandidateRuntime getWorkerCandidateRuntime();

    WorkerSchedulingViewRuntime getWorkerSchedulingViewRuntime();

    WorkerResourceRuntime getWorkerResourceRuntime();

    WorkerControlRuntime getWorkerControlRuntime();

    AssignmentDiagnosticRecorder getRecordService();

    TraceEventLogger getTraceEventLogger();

    TaskWorkerMatchingStrategy getMatchingStrategy();

    MatchingRuleSetProvider getMatchingRuleSetProvider();

    MatchingRuleEvaluator<Map<String, Object>> getMatchingRuleEvaluator();

    long getAssignmentRetryDelayMillis();

    long getRuntimeReadyDispatchIntervalMillis();

    long getRuntimeReadyDispatchIdleBackoffMaxMillis();

    PollingIdleBackoffPolicy getRuntimeReadyDispatchIdleBackoffPolicy();

    long getLeaseWatchdogIntervalSeconds();

    long getWorkerCommandMaintenanceIntervalSeconds();

    int getWorkerCommandMaintenanceScanLimit();

    int getWorkerCommandDeliveryMaxAttempts();
}
