package com.xa.mass.worker.runtime;

import java.net.URI;
import java.util.Objects;

/**
 * Common lifecycle and observation contract for assembled Worker instances.
 */
public interface WorkerLifecycle extends AutoCloseable {

    enum State {
        STOPPED,
        RUNNING
    }

    enum StopReason { MANUAL, COOLDOWN, UNAVAILABLE, REJECTED, TERMINATED }

    @FunctionalInterface
    interface Listener {

        /** Receives a best-effort level observation, not an ordered log. */
        void onSnapshot(Snapshot snapshot);
    }

    /**
     * Requests one startup attempt and returns after the request is accepted.
     * Ordinary startup failures are reported through {@link #snapshot()}.
     */
    void start();

    /**
     * Requests that the current startup attempt or runtime stop.
     *
     * <p>An active runtime is revoked before its Client is closed. A
     * preparation already in progress remains single-flight, but its result
     * cannot install a Transport after this request.
     */
    void stop();

    Snapshot snapshot();

    void addListener(Listener listener);

    void removeListener(Listener listener);

    @Override
    void close();

    final class Snapshot {

        private final State state;
        private final String workerId;
        private final URI endpointUri;
        private final String diagnosticMessage;
        private final StopReason stopReason;
        private final Long cooldownUntilMillis;

        public Snapshot(
                State state,
                String workerId,
                URI endpointUri,
                String diagnosticMessage
        ) {
            this(state, workerId, endpointUri, diagnosticMessage, null, null);
        }

        public Snapshot(State state, String workerId, URI endpointUri, String diagnosticMessage,
                        StopReason stopReason, Long cooldownUntilMillis) {
            this.state = Objects.requireNonNull(state, "state");
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.diagnosticMessage = diagnosticMessage;
            this.stopReason = stopReason;
            this.cooldownUntilMillis = cooldownUntilMillis;
        }

        public State state() {
            return state;
        }

        public String workerId() {
            return workerId;
        }

        public URI endpointUri() {
            return endpointUri;
        }

        public String diagnosticMessage() {
            return diagnosticMessage;
        }

        public StopReason stopReason() { return stopReason; }
        public Long cooldownUntilMillis() { return cooldownUntilMillis; }
    }
}
