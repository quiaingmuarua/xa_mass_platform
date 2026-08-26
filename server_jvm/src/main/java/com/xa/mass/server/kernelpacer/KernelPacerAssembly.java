package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.assembly.KernelPacerPolicyConfig;
import com.xa.mass.kernel.assignment.AssignmentDispatchApplication;
import com.xa.mass.kernel.result.ResultRoutingApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultApplication;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

public final class KernelPacerAssembly
        implements SmartLifecycle, DisposableBean {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
        STOPPING
    }

    public record Snapshot(
            boolean enabled,
            State state,
            String resultRoutingState,
            String workerServiceabilityResultState,
            String workerServiceabilityDispatchState,
            String assignmentDispatchState
    ) {
    }

    private final KernelPacerProperties properties;
    private final KernelPacerPolicyConfig policy;
    private final ResultRoutingApplication resultRouting;
    private final WorkerServiceabilityResultApplication serviceabilityResult;
    private final WorkerServiceabilityDispatchApplication
            serviceabilityDispatch;
    private final AssignmentDispatchApplication assignmentDispatch;
    private State state = State.STOPPED;

    KernelPacerAssembly(
            KernelPacerProperties properties,
            KernelPacerPolicyConfig policy,
            ResultRoutingApplication resultRouting,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            AssignmentDispatchApplication assignmentDispatch
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resultRouting = Objects.requireNonNull(
                resultRouting,
                "resultRouting"
        );
        this.serviceabilityResult = Objects.requireNonNull(
                serviceabilityResult,
                "serviceabilityResult"
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

    @Override
    public synchronized void start() {
        if (!properties.enabled()) {
            return;
        }
        if (state != State.STOPPED) {
            throw new IllegalStateException(
                    "operation=kernelPacer.start invalid state=" + state
            );
        }
        state = State.STARTING;
        boolean resultStarted = false;
        boolean serviceabilityResultStarted = false;
        boolean serviceabilityDispatchStarted = false;
        boolean assignmentStarted = false;
        try {
            resultRouting.start(policy.resultRouting());
            resultStarted = true;
            if (policy.workerServiceability().enabled()) {
                long floor = policy.workerServiceability()
                        .hotEligibilityFloorMillis();
                serviceabilityResult.start(
                        policy.workerServiceability().result(),
                        floor
                );
                serviceabilityResultStarted = true;
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
                    serviceabilityResultStarted,
                    serviceabilityDispatchStarted,
                    assignmentStarted
            );
            state = State.FAILED;
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        if (!properties.enabled() || state == State.STOPPED) {
            return;
        }
        state = State.STOPPING;
        RuntimeException firstFailure = null;
        long deadline = System.nanoTime()
                + properties.shutdownTimeout().toNanos();
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
            try {
                serviceabilityResult.stop(remainingMillis(deadline));
            } catch (RuntimeException failure) {
                firstFailure = accumulate(firstFailure, failure);
            }
        }
        try {
            resultRouting.stop(remainingMillis(deadline));
        } catch (RuntimeException failure) {
            firstFailure = accumulate(firstFailure, failure);
        }
        if (firstFailure == null) {
            state = State.STOPPED;
        } else {
            state = State.FAILED;
            throw firstFailure;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        refreshUnexpectedExit();
        return state == State.RUNNING;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    public synchronized Snapshot snapshot() {
        refreshUnexpectedExit();
        boolean serviceabilityEnabled = policy.workerServiceability()
                .enabled();
        return new Snapshot(
                properties.enabled(),
                state,
                resultRouting.state(),
                serviceabilityEnabled
                        ? serviceabilityResult.state()
                        : "DISABLED",
                serviceabilityEnabled
                        ? serviceabilityDispatch.state()
                        : "DISABLED",
                assignmentDispatch.state()
        );
    }

    @Override
    public void destroy() {
        stop();
    }

    private void refreshUnexpectedExit() {
        if (state == State.RUNNING
                && (!resultRouting.isRunning()
                || !assignmentDispatch.isRunning()
                || policy.workerServiceability().enabled()
                && (!serviceabilityResult.isRunning()
                || !serviceabilityDispatch.isRunning()))) {
            state = State.FAILED;
        }
    }

    private void rollbackStarted(
            RuntimeException startFailure,
            boolean resultStarted,
            boolean serviceabilityResultStarted,
            boolean serviceabilityDispatchStarted,
            boolean assignmentStarted
    ) {
        long deadline = System.nanoTime()
                + properties.shutdownTimeout().toNanos();
        if (assignmentStarted) {
            try {
                assignmentDispatch.stop(remainingMillis(deadline));
            } catch (RuntimeException rollbackFailure) {
                startFailure.addSuppressed(rollbackFailure);
            }
        }
        if (serviceabilityDispatchStarted) {
            try {
                serviceabilityDispatch.stop(remainingMillis(deadline));
            } catch (RuntimeException rollbackFailure) {
                startFailure.addSuppressed(rollbackFailure);
            }
        }
        if (serviceabilityResultStarted) {
            try {
                serviceabilityResult.stop(remainingMillis(deadline));
            } catch (RuntimeException rollbackFailure) {
                startFailure.addSuppressed(rollbackFailure);
            }
        }
        if (resultStarted) {
            try {
                resultRouting.stop(remainingMillis(deadline));
            } catch (RuntimeException rollbackFailure) {
                startFailure.addSuppressed(rollbackFailure);
            }
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
}
