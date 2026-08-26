package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.result.ResultRoutingApplication;
import com.xa.mass.kernel.result.ResultRoutingApplicationConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityAssemblyConfig;
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
            Long pid,
            String resultRoutingState,
            String workerServiceabilityResultState,
            String workerServiceabilityDispatchState
    ) {
    }

    private final KernelPacerProperties properties;
    private final PythonKernelPacerProcess pythonProcess;
    private final ResultRoutingApplication resultRouting;
    private final ResultRoutingApplicationConfig resultRoutingConfig;
    private final WorkerServiceabilityResultApplication serviceabilityResult;
    private final WorkerServiceabilityDispatchApplication
            serviceabilityDispatch;
    private final WorkerServiceabilityAssemblyConfig
            serviceabilityConfig;
    private State state = State.STOPPED;

    KernelPacerAssembly(
            KernelPacerProperties properties,
            PythonKernelPacerProcess pythonProcess,
            ResultRoutingApplication resultRouting,
            ResultRoutingApplicationConfig resultRoutingConfig,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            WorkerServiceabilityAssemblyConfig serviceabilityConfig
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.pythonProcess = Objects.requireNonNull(
                pythonProcess,
                "pythonProcess"
        );
        this.resultRouting = Objects.requireNonNull(
                resultRouting,
                "resultRouting"
        );
        this.resultRoutingConfig = Objects.requireNonNull(
                resultRoutingConfig,
                "resultRoutingConfig"
        );
        this.serviceabilityResult = Objects.requireNonNull(
                serviceabilityResult,
                "serviceabilityResult"
        );
        this.serviceabilityDispatch = Objects.requireNonNull(
                serviceabilityDispatch,
                "serviceabilityDispatch"
        );
        this.serviceabilityConfig = Objects.requireNonNull(
                serviceabilityConfig,
                "serviceabilityConfig"
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
        boolean resultRoutingStarted = false;
        boolean serviceabilityResultStarted = false;
        boolean serviceabilityDispatchStarted = false;
        try {
            resultRouting.start(resultRoutingConfig);
            resultRoutingStarted = true;
            if (serviceabilityConfig.enabled()) {
                serviceabilityResult.start(
                        serviceabilityConfig.result(),
                        serviceabilityConfig.hotEligibilityFloorMillis()
                );
                serviceabilityResultStarted = true;
                serviceabilityDispatch.start(
                        serviceabilityConfig.dispatch(),
                        serviceabilityConfig.hotEligibilityFloorMillis()
                );
                serviceabilityDispatchStarted = true;
            }
            pythonProcess.start();
            state = State.RUNNING;
        } catch (RuntimeException failure) {
            rollbackStarted(
                    failure,
                    resultRoutingStarted,
                    serviceabilityResultStarted,
                    serviceabilityDispatchStarted
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
            pythonProcess.stop(remaining(deadline));
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        if (serviceabilityConfig.enabled()) {
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
        return new Snapshot(
                properties.enabled(),
                state,
                properties.enabled() ? pythonProcess.pid() : null,
                resultRouting.state(),
                serviceabilityConfig.enabled()
                        ? serviceabilityResult.state()
                        : "DISABLED",
                serviceabilityConfig.enabled()
                        ? serviceabilityDispatch.state()
                        : "DISABLED"
        );
    }

    @Override
    public void destroy() {
        // DefaultLifecycleProcessor may skip stop() after an unexpected child
        // exit because isRunning() is then false. Bean destruction remains an
        // unconditional cleanup boundary for the current child's state files.
        stop();
    }

    private void refreshUnexpectedExit() {
        if (state == State.RUNNING
                && (!pythonProcess.isAlive()
                || !resultRouting.isRunning()
                || serviceabilityConfig.enabled()
                && (!serviceabilityResult.isRunning()
                || !serviceabilityDispatch.isRunning()))) {
            state = State.FAILED;
        }
    }

    private void rollbackStarted(
            RuntimeException startFailure,
            boolean resultRoutingStarted,
            boolean serviceabilityResultStarted,
            boolean serviceabilityDispatchStarted
    ) {
        long deadline = System.nanoTime()
                + properties.shutdownTimeout().toNanos();
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
        if (resultRoutingStarted) {
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

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(
                1,
                deadlineNanos - System.nanoTime()
        ));
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
