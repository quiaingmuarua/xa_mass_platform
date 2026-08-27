package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
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
            String workerServiceabilityDispatchState,
            String assignmentDispatchState
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(
                    resultConvergenceState,
                    "resultConvergenceState"
            );
            Objects.requireNonNull(
                    workerServiceabilityDispatchState,
                    "workerServiceabilityDispatchState"
            );
            Objects.requireNonNull(
                    assignmentDispatchState,
                    "assignmentDispatchState"
            );
        }
    }

    private final Duration shutdownTimeout;
    private final KernelPacerPolicyConfig policy;
    private final ResultConvergenceApplication resultConvergence;
    private final WorkerServiceabilityDispatchApplication
            serviceabilityDispatch;
    private final AssignmentDispatchApplication assignmentDispatch;
    private State state = State.STOPPED;

    KernelPacerRuntime(
            Duration shutdownTimeout,
            KernelPacerPolicyConfig policy,
            ResultConvergenceApplication resultConvergence,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            AssignmentDispatchApplication assignmentDispatch
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
        this.serviceabilityDispatch = Objects.requireNonNull(
                serviceabilityDispatch,
                "serviceabilityDispatch"
        );
        this.assignmentDispatch = Objects.requireNonNull(
                assignmentDispatch,
                "assignmentDispatch"
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
            CandidateWorkerCache candidateCache,
            CandidateWarmupSchedule warmups
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
        WorkerServiceabilityDispatchApplication serviceabilityDispatch =
                new WorkerServiceabilityDispatchApplication(
                        new WorkerServiceabilityDispatchPacer(
                                Objects.requireNonNull(
                                        taskScores,
                                        "taskScores"
                                ),
                                Objects.requireNonNull(
                                        taskCatalog,
                                        "taskCatalog"
                                ),
                                workerScores,
                                workerCatalog,
                                serviceability
                        )
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
                        AssignmentDispatchApplicationConfig.WORKER_SCAN_LIMIT,
                        serviceabilityPolicy.enabled()
                                ? serviceabilityPolicy
                                .hotEligibilityFloorMillis()
                                : null
                );
        TaskWorkerAllocationPacer allocation =
                new TaskWorkerAllocationPacer(
                        Objects.requireNonNull(warmups, "warmups"),
                        taskScores,
                        taskCatalog,
                        candidateAcquirer,
                        candidateCache
                );
        TaskRunningActivationPacer activation =
                new TaskRunningActivationPacer(
                        taskScores,
                        itemScores,
                        taskCatalog,
                        warmups
                );
        TaskItemDispatcher itemDispatcher = new TaskItemDispatcher(
                itemScores,
                taskRuntime,
                candidateAcquirer,
                warmups,
                new ResultContextCodec()
        );
        TaskDispatchPacer dispatch = new TaskDispatchPacer(
                taskScores,
                taskCatalog,
                Objects.requireNonNull(workerCommands, "workerCommands"),
                itemScores,
                itemDispatcher
        );
        AssignmentDispatchApplication assignmentDispatch =
                new AssignmentDispatchApplication(
                        allocation,
                        activation,
                        dispatch
                );
        return new KernelPacerRuntime(
                shutdownTimeout,
                policy,
                resultConvergence,
                serviceabilityDispatch,
                assignmentDispatch
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
        boolean serviceabilityDispatchStarted = false;
        boolean assignmentStarted = false;
        try {
            resultConvergence.start();
            resultStarted = true;
            if (policy.workerServiceability().enabled()) {
                long floor = policy.workerServiceability()
                        .hotEligibilityFloorMillis();
                serviceabilityDispatch.start(
                        policy.workerServiceability().dispatch(),
                        floor
                );
                serviceabilityDispatchStarted = true;
            }
            assignmentDispatch.start(policy.assignmentDispatch());
            assignmentStarted = true;
            state = State.RUNNING;
        } catch (RuntimeException failure) {
            rollbackStarted(
                    failure,
                    resultStarted,
                    serviceabilityDispatchStarted,
                    assignmentStarted
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
            assignmentDispatch.stop(remainingMillis(deadline));
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        if (policy.workerServiceability().enabled()) {
            try {
                serviceabilityDispatch.stop(remainingMillis(deadline));
            } catch (RuntimeException failure) {
                firstFailure = accumulate(firstFailure, failure);
            }
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
        boolean serviceabilityEnabled = policy.workerServiceability()
                .enabled();
        return new Snapshot(
                state,
                resultConvergence.state(),
                serviceabilityEnabled
                        ? serviceabilityDispatch.state()
                        : "DISABLED",
                assignmentDispatch.state()
        );
    }

    private void refreshUnexpectedExit() {
        if (state == State.RUNNING
                && (!resultConvergence.isRunning()
                || !assignmentDispatch.isRunning()
                || policy.workerServiceability().enabled()
                && !serviceabilityDispatch.isRunning())) {
            state = State.FAILED;
        }
    }

    private void rollbackStarted(
            RuntimeException startFailure,
            boolean resultStarted,
            boolean serviceabilityDispatchStarted,
            boolean assignmentStarted
    ) {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        if (assignmentStarted) {
            stopForRollback(
                    startFailure,
                    () -> assignmentDispatch.stop(remainingMillis(deadline))
            );
        }
        if (serviceabilityDispatchStarted) {
            stopForRollback(
                    startFailure,
                    () -> serviceabilityDispatch.stop(
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
