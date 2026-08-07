package com.xa.mass.worker.runtime;

import java.net.URI;
import java.util.Objects;

/**
 * Common lifecycle and observation contract for assembled Worker instances.
 */
public interface WorkerLifecycle extends AutoCloseable {

    enum State {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR,
        CLOSED
    }

    enum PrepareOperation {
        NONE,
        REGISTERING,
        BINDING
    }

    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
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
        private final PrepareOperation prepareOperation;
        private final ConnectionState connectionState;
        private final String workerId;
        private final URI endpointUri;
        private final String diagnosticMessage;

        public Snapshot(
                State state,
                PrepareOperation prepareOperation,
                ConnectionState connectionState,
                String workerId,
                URI endpointUri,
                String diagnosticMessage
        ) {
            this.state = Objects.requireNonNull(state, "state");
            this.prepareOperation = Objects.requireNonNull(
                    prepareOperation,
                    "prepareOperation"
            );
            this.connectionState = Objects.requireNonNull(
                    connectionState,
                    "connectionState"
            );
            this.workerId = workerId;
            this.endpointUri = endpointUri;
            this.diagnosticMessage = diagnosticMessage;
        }

        public State state() {
            return state;
        }

        public PrepareOperation prepareOperation() {
            return prepareOperation;
        }

        public ConnectionState connectionState() {
            return connectionState;
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
