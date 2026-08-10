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

    @FunctionalInterface
    interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    /**
     * Performs one startup attempt synchronously on the calling thread.
     * Ordinary startup failures are reported through {@link #snapshot()}.
     */
    void start();

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

        public Snapshot(
                State state,
                String workerId,
                URI endpointUri,
                String diagnosticMessage
        ) {
            this.state = Objects.requireNonNull(state, "state");
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.diagnosticMessage = diagnosticMessage;
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
    }
}
