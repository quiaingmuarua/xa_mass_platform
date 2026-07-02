package com.xa.mass.task.runtime.starter;

import java.util.List;

public final class TaskRuntimeHandle implements AutoCloseable {

    private final TaskRuntimeBackendKind backendKind;
    private final TaskRuntimePortSet runtime;
    private final TaskRuntimeLoopHost loopHost;
    private final AutoCloseable closeableRuntime;

    TaskRuntimeHandle(
            TaskRuntimeBackendKind backendKind,
            TaskRuntimePortSet runtime,
            TaskRuntimeLoopHost loopHost,
            AutoCloseable closeableRuntime
    ) {
        this.backendKind = backendKind;
        this.runtime = runtime;
        this.loopHost = loopHost;
        this.closeableRuntime = closeableRuntime;
    }

    public TaskRuntimeBackendKind backendKind() {
        return backendKind;
    }

    public TaskRuntimePortSet runtime() {
        return runtime;
    }

    public TaskRuntimeLoopHostStatus status() {
        return loopHost.status();
    }

    public void start() {
        loopHost.start();
    }

    public void stop() {
        loopHost.stop();
    }

    public void registerLoops(List<TaskRuntimeLoop> loops) {
        loopHost.registerLoops(loops);
    }

    @Override
    public void close() {
        stop();
        if (closeableRuntime != null) {
            try {
                closeableRuntime.close();
            } catch (Exception exception) {
                throw new IllegalStateException("failed to close task runtime backend", exception);
            }
        }
    }
}
