package com.xa.mass.client.worker.session;

public interface WorkerSessionListener {
    WorkerSessionListener NOOP = new WorkerSessionListener() {
    };

    default void onStartupFailure(WorkerSessionStartupFailure failure) {
    }

    default void onHandlerFailure(WorkerSessionDispatchFailure failure) {
    }

    default void onSubmitFailure(WorkerSessionDispatchFailure failure) {
    }

    default void onQueuedResultDropped(WorkerSessionQueuedResultFailure failure) {
    }

    default void onQueuedResultAbandoned(WorkerSessionQueuedResultFailure failure) {
    }

    default void onPollFailure(WorkerSessionPollFailure failure) {
    }

    default void onConnectionFailure(WorkerSessionConnectionFailure failure) {
    }

    default void onShutdownFailure(String workerId, Throwable failure) {
    }
}
