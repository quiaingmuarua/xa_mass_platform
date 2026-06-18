package com.xa.mass.client.worker.runtime;

public interface WorkerRuntimeListener {
    WorkerRuntimeListener NOOP = new WorkerRuntimeListener() {
    };

    default void onStartupFailure(WorkerRuntimeStartupFailure failure) {
    }

    default void onHandlerFailure(WorkerRuntimeDispatchFailure failure) {
    }

    default void onSubmitFailure(WorkerRuntimeDispatchFailure failure) {
    }

    default void onQueuedResultDropped(WorkerRuntimeQueuedResultFailure failure) {
    }

    default void onQueuedResultAbandoned(WorkerRuntimeQueuedResultFailure failure) {
    }

    default void onPollFailure(WorkerRuntimePollFailure failure) {
    }

    default void onHeartbeatFailure(WorkerRuntimeHeartbeatFailure failure) {
    }

    default void onConnectionFailure(WorkerRuntimeConnectionFailure failure) {
    }

    default void onConnectionRecovered(String workerId) {
    }

    default void onFrameFailure(WorkerRuntimeFrameFailure failure) {
    }

    default void onShutdownFailure(String workerId, Throwable failure) {
    }
}
