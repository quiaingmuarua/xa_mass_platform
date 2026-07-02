package com.xa.mass.task.runtime.starter;

import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;

import java.util.List;

public final class TaskRuntimeHandle implements AutoCloseable {

    private final TaskRuntimeBackendKind backendKind;
    private final TaskRuntimePortSet runtime;
    private final TaskRuntimeResultWindowReadModel resultWindowReadModel;
    private final TaskRuntimeLoopHost loopHost;
    private final AutoCloseable closeableRuntime;

    TaskRuntimeHandle(
            TaskRuntimeBackendKind backendKind,
            TaskRuntimePortSet runtime,
            TaskRuntimeResultWindowReadModel resultWindowReadModel,
            TaskRuntimeLoopHost loopHost,
            AutoCloseable closeableRuntime
    ) {
        this.backendKind = backendKind;
        this.runtime = runtime;
        this.resultWindowReadModel = resultWindowReadModel;
        this.loopHost = loopHost;
        this.closeableRuntime = closeableRuntime;
    }

    public TaskRuntimeBackendKind backendKind() {
        return backendKind;
    }

    public TaskRuntimePortSet runtime() {
        return runtime;
    }

    public TaskRuntimeResultWindowReadModel resultWindowReadModel() {
        return resultWindowReadModel;
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
