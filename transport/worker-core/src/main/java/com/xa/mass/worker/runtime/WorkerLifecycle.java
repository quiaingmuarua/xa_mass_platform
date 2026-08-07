package com.xa.mass.worker.runtime;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;

import java.net.URI;
import java.util.Objects;

/**
 * Common lifecycle and observation contract for assembled Worker instances.
 */
public interface WorkerLifecycle extends AutoCloseable {

    enum State {
        STOPPED,
        PREPARING,
        RUNNING,
        ERROR,
        CLOSED
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

    boolean send(WorkerCommand command);

    Snapshot snapshot();

    boolean isConnected();

    void addListener(Listener listener);

    void removeListener(Listener listener);

    @Override
    void close();

    final class Snapshot {

        private final State state;
        private final ConnectionState connectionState;
        private final String workerId;
        private final URI endpointUri;
        private final String diagnosticMessage;

        public Snapshot(
                State state,
                ConnectionState connectionState,
                String workerId,
                URI endpointUri,
                String diagnosticMessage
        ) {
            this.state = Objects.requireNonNull(state, "state");
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
