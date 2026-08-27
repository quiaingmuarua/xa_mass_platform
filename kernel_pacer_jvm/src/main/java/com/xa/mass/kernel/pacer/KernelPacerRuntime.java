package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single assembly and lifecycle boundary for all production Kernel Pacers.
 *
 * <p>The runtime owns only fixed policy selection and Pacer threads. The
 * mechanical owners supplied to {@link #assemble} retain their own lifecycle
 * and storage ownership.</p>
 */
public final class KernelPacerRuntime {

    public enum PolicyPreset {
        DEFAULT,
        SERVICEABILITY_DEFAULT,
        SCENARIO_LAB,
        RUNTIME_BOUNDARY_PROOF
    }

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
        STOPPING
    }

    public record Snapshot(
            State state,
            String resultConvergenceState,
            String dispatchConvergenceState
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(
                    resultConvergenceState,
                    "resultConvergenceState"
            );
            Objects.requireNonNull(
                    dispatchConvergenceState,
                    "dispatchConvergenceState"
            );
        }
    }

    private final Duration shutdownTimeout;
    private final KernelPacerPolicyConfig policy;
    private final ResultConvergenceApplication resultConvergence;
    private final DispatchConvergenceApplication dispatchConvergence;
    private State state = State.STOPPED;

    KernelPacerRuntime(
            Duration shutdownTimeout,
            KernelPacerPolicyConfig policy,
            ResultConvergenceApplication resultConvergence,
            DispatchConvergenceApplication dispatchConvergence
    ) {
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resultConvergence = Objects.requireNonNull(
                resultConvergence,
                "resultConvergence"
        );
        this.dispatchConvergence = Objects.requireNonNull(
                dispatchConvergence,
                "dispatchConvergence"
        );
    }

    public static KernelPacerRuntime assemble(
            PolicyPreset policyPreset,
            Duration shutdownTimeout,
            TaskResultRuntime taskResults,
            TaskRuntime taskRuntime,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            CandidateWorkerCache candidateCache
    ) {
        KernelPacerPolicyConfig policy = KernelPacerPolicyConfig.forPreset(
                Objects.requireNonNull(policyPreset, "policyPreset")
        );
        Objects.requireNonNull(taskResults, "taskResults");
        Objects.requireNonNull(taskRuntime, "taskRuntime");
        Objects.requireNonNull(itemScores, "itemScores");
        Objects.requireNonNull(workerScores, "workerScores");
        Objects.requireNonNull(serviceability, "serviceability");
        Objects.requireNonNull(workerCatalog, "workerCatalog");
        ResultConvergenceConfig convergencePolicy =
                policy.resultConvergence();
        TaskResultBatchPolicy taskResultPolicy = new TaskResultBatchPolicy(
                taskRuntime,
                itemScores,
                workerScores
        );
        List<ResultLane> resultLanes = new ArrayList<>();
        resultLanes.add(new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                ResultConvergenceConfig.TASK_RESULT_BATCH_LIMIT,
                convergencePolicy.taskResultIdleIntervalMillis(),
                ResultConvergenceConfig
                        .TASK_SUCCESS_TARGET_CONCURRENCY,
                ResultConvergenceConfig
                        .TASK_SUCCESS_MAX_CONCURRENCY,
                limit -> taskResults.consumeTaskResults(
                        TaskResultClass.SUCCESS,
                        limit
                ),
                taskResultPolicy::handleSuccess
        ));
        resultLanes.add(new ResultLane(
                ResultLaneId.TASK_FAILURE,
                ResultConvergenceConfig.TASK_RESULT_BATCH_LIMIT,
                convergencePolicy.taskResultIdleIntervalMillis(),
                ResultConvergenceConfig
                        .TASK_FAILURE_TARGET_CONCURRENCY,
                ResultConvergenceConfig
                        .TASK_FAILURE_MAX_CONCURRENCY,
                limit -> taskResults.consumeTaskResults(
                        TaskResultClass.FAILURE,
                        limit
                ),
                taskResultPolicy::handleFailure
        ));
        WorkerServiceabilityAssemblyConfig serviceabilityPolicy =
                policy.workerServiceability();
        if (serviceabilityPolicy.enabled()) {
            WorkerServiceabilityResultConfig resultPolicy =
                    serviceabilityPolicy.result();
            WorkerServiceabilityResultPolicy evidencePolicy =
                    new WorkerServiceabilityResultPolicy(
                            workerCatalog,
                            workerScores,
                            resultPolicy,
                            serviceabilityPolicy.hotEligibilityFloorMillis()
                    );
            resultLanes.add(new ResultLane(
                    ResultLaneId.ADAPTER_EVIDENCE,
                    resultPolicy.resultReportLimit(),
                    convergencePolicy.adapterEvidenceIdleIntervalMillis(),
                    ResultConvergenceConfig
                            .ADAPTER_EVIDENCE_TARGET_CONCURRENCY,
                    ResultConvergenceConfig
                            .ADAPTER_EVIDENCE_MAX_CONCURRENCY,
                    serviceability::consumeAdapterEvidenceResults,
                    evidencePolicy::handle
            ));
        }
        ResultConvergenceApplication resultConvergence =
                new ResultConvergenceApplication(
                        resultLanes,
                        ResultConvergenceConfig.GLOBAL_MAX_CONCURRENCY
                );
        TaskScoreBandCore requiredTaskScores = Objects.requireNonNull(
                taskScores,
                "taskScores"
        );
        TaskResourceCatalog requiredTaskCatalog = Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        WorkerCandidateMatcher matcher = new WorkerCandidateMatcher(
                workerCatalog
        );
        WorkerCandidateAcquirer candidateAcquirer =
                new WorkerCandidateAcquirer(
                        Objects.requireNonNull(
                                candidateCache,
                                "candidateCache"
                        ),
                        workerScores,
                        matcher,
                        AssignmentDispatchConfig.WORKER_SCAN_LIMIT,
                        serviceabilityPolicy.enabled()
                                ? serviceabilityPolicy
                                .hotEligibilityFloorMillis()
                                : null
                );
        TaskWorkerAllocationPolicy allocation =
                new TaskWorkerAllocationPolicy(
                        candidateAcquirer,
                        candidateCache
                );
        TaskInitializationPolicy initialization =
                new TaskInitializationPolicy(
                        requiredTaskScores,
                        itemScores
                );
        TaskItemDispatcher itemDispatcher = new TaskItemDispatcher(
                itemScores,
                taskRuntime,
                candidateAcquirer,
                new ResultContextCodec()
        );
        TaskDispatchPolicy dispatch = new TaskDispatchPolicy(
                requiredTaskScores,
                Objects.requireNonNull(workerCommands, "workerCommands"),
                itemScores,
                itemDispatcher
        );
        WorkerServiceabilityDispatchPolicy serviceabilityDispatch =
                new WorkerServiceabilityDispatchPolicy(
                        workerScores,
                        workerCatalog,
                        serviceability
                );
        DispatchConvergenceApplication dispatchConvergence =
                new DispatchConvergenceApplication(
                        new TaskSchedulingBatchSource(
                                requiredTaskScores,
                                requiredTaskCatalog
                        ),
                        initialization,
                        allocation,
                        dispatch,
                        serviceabilityDispatch
                );
        return new KernelPacerRuntime(
                shutdownTimeout,
                policy,
                resultConvergence,
                dispatchConvergence
        );
    }

    public synchronized void start() {
        if (state != State.STOPPED) {
            throw new IllegalStateException(
                    "operation=kernelPacer.start invalid state=" + state
            );
        }
        state = State.STARTING;
        boolean resultStarted = false;
        boolean dispatchStarted = false;
        try {
            resultConvergence.start();
            resultStarted = true;
            dispatchConvergence.start(
                    policy.assignmentDispatch(),
                    policy.workerServiceability()
            );
            dispatchStarted = true;
            state = State.RUNNING;
        } catch (RuntimeException failure) {
            rollbackStarted(
                    failure,
                    resultStarted,
                    dispatchStarted
            );
            state = State.FAILED;
            throw failure;
        }
    }

    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        state = State.STOPPING;
        RuntimeException firstFailure = null;
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        try {
            dispatchConvergence.stop(remainingMillis(deadline));
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        try {
            resultConvergence.stop(remainingMillis(deadline));
        } catch (RuntimeException failure) {
            firstFailure = accumulate(firstFailure, failure);
        }
        if (firstFailure == null) {
            state = State.STOPPED;
            return;
        }
        state = State.FAILED;
        throw firstFailure;
    }

    public synchronized boolean isRunning() {
        refreshUnexpectedExit();
        return state == State.RUNNING;
    }

    public synchronized Snapshot snapshot() {
        refreshUnexpectedExit();
        return new Snapshot(
                state,
                resultConvergence.state(),
                dispatchConvergence.state()
        );
    }

    private void refreshUnexpectedExit() {
        if (state == State.RUNNING
                && (!resultConvergence.isRunning()
                || !dispatchConvergence.isRunning())) {
            state = State.FAILED;
        }
    }

    private void rollbackStarted(
            RuntimeException startFailure,
            boolean resultStarted,
            boolean dispatchStarted
    ) {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        if (dispatchStarted) {
            stopForRollback(
                    startFailure,
                    () -> dispatchConvergence.stop(
                            remainingMillis(deadline)
                    )
            );
        }
        if (resultStarted) {
            stopForRollback(
                    startFailure,
                    () -> resultConvergence.stop(remainingMillis(deadline))
            );
        }
    }

    private static void stopForRollback(
            RuntimeException startFailure,
            Runnable stop
    ) {
        try {
            stop.run();
        } catch (RuntimeException rollbackFailure) {
            startFailure.addSuppressed(rollbackFailure);
        }
    }

    private static RuntimeException accumulate(
            RuntimeException first,
            RuntimeException next
    ) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(
                1,
                Duration.ofNanos(Math.max(
                        1,
                        deadlineNanos - System.nanoTime()
                )).toMillis()
        );
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
