package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.pacer.dispatch.DispatchConvergenceRuntime;
import com.xa.mass.kernel.pacer.result.ResultConvergenceRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.DefaultTaskItemResultEvents;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.DefaultWorkerExecutionResultEvents;
import com.xa.mass.kernel.worker.DefaultWorkerServiceabilityEvents;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.time.Duration;
import java.util.Objects;

/**
 * The single external assembly and lifecycle boundary for all production
 * Kernel Pacers.
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
    private final ResultConvergenceRuntime resultConvergence;
    private final DispatchConvergenceRuntime dispatchConvergence;
    private State state = State.STOPPED;

    KernelPacerRuntime(
            Duration shutdownTimeout,
            ResultConvergenceRuntime resultConvergence,
            DispatchConvergenceRuntime dispatchConvergence
    ) {
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
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
        ResultConvergenceRuntime resultConvergence =
                ResultConvergenceRuntime.assemble(
                        policy.preset(),
                        policy.hotEligibilityFloorMillis(),
                        taskResults,
                        new DefaultTaskItemResultEvents(
                                taskRuntime,
                                itemScores
                        ),
                        new DefaultWorkerExecutionResultEvents(
                                workerScores
                        ),
                        new DefaultWorkerServiceabilityEvents(
                                workerCatalog,
                                workerScores
                        ),
                        serviceability
                );
        DispatchConvergenceRuntime dispatchConvergence =
                DispatchConvergenceRuntime.assemble(
                        policy.preset(),
                        policy.hotEligibilityFloorMillis(),
                        taskRuntime,
                        taskScores,
                        itemScores,
                        taskCatalog,
                        workerScores,
                        workerCatalog,
                        workerCommands,
                        serviceability,
                        candidateCache
                );
        return new KernelPacerRuntime(
                shutdownTimeout,
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
            dispatchConvergence.start();
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
