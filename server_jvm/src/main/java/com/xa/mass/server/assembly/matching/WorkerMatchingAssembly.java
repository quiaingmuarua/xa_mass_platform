package com.xa.mass.server.assembly.matching;

import com.xa.mass.workermatching.WorkerMatchingRuntime;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

public final class WorkerMatchingAssembly
        implements SmartLifecycle, DisposableBean {

    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final WorkerMatchingRuntime runtime;

    WorkerMatchingAssembly(WorkerMatchingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void start() {
        runtime.start();
    }

    @Override
    public void stop() {
        runtime.stop(SHUTDOWN_TIMEOUT_MILLIS);
    }

    @Override
    public boolean isRunning() {
        return runtime.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    public WorkerMatchingRuntime.Snapshot snapshot() {
        return runtime.snapshot();
    }

    @Override
    public void destroy() {
        runtime.close();
    }
}
