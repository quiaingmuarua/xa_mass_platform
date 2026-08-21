package com.xa.mass.server.kernelpacer;

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

    public record Snapshot(boolean enabled, State state, Long pid) {
    }

    private final KernelPacerProperties properties;
    private final PythonKernelPacerProcess pythonProcess;
    private State state = State.STOPPED;

    KernelPacerAssembly(
            KernelPacerProperties properties,
            PythonKernelPacerProcess pythonProcess
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.pythonProcess = Objects.requireNonNull(
                pythonProcess,
                "pythonProcess"
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
        try {
            pythonProcess.start();
            state = State.RUNNING;
        } catch (RuntimeException failure) {
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
        try {
            pythonProcess.stop();
            state = State.STOPPED;
        } catch (RuntimeException failure) {
            state = State.FAILED;
            throw failure;
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
                properties.enabled() ? pythonProcess.pid() : null
        );
    }

    @Override
    public void destroy() {
        // DefaultLifecycleProcessor may skip stop() after an unexpected child
        // exit because isRunning() is then false. Bean destruction remains an
        // unconditional cleanup boundary for owner and ready files.
        stop();
    }

    private void refreshUnexpectedExit() {
        if (state == State.RUNNING && !pythonProcess.isAlive()) {
            state = State.FAILED;
        }
    }
}
