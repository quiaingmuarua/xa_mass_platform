package com.xa.mass.transport.channel;

/**
 * No-op system-event channel used when a runtime does not wire an explicit event sink.
 */
public final class NoopWorkerSystemEventChannel implements WorkerSystemEventChannel {

    public static final NoopWorkerSystemEventChannel INSTANCE = new NoopWorkerSystemEventChannel();

    private NoopWorkerSystemEventChannel() {
    }

    @Override
    public void publishWorkerOnline(String workerId, String reason, String traceId) {
    }

    @Override
    public void publishWorkerOffline(String workerId, String reason, String traceId) {
    }
}
