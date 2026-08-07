package com.xa.mass.worker.runtime;

import java.net.URI;

/**
 * Common lifecycle and observation contract for assembled Worker instances.
 */
public interface WorkerLifecycle extends AutoCloseable {

    enum State {
        STOPPED,
        STARTING,
        REGISTERING,
        BINDING,
        CONNECTING,
        TRANSPORT_CONNECTED,
        ERROR,
        CLOSED
    }

    @FunctionalInterface
    interface Listener {

        void onSnapshot(Snapshot snapshot);
    }

    void start();

    void stop();

    void refreshProperties();

    Snapshot snapshot();

    boolean isConnected();

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
            this.state = state;
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
