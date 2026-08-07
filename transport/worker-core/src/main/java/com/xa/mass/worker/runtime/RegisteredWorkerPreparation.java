package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Restores or registers one Worker identity and refreshes its endpoint bind.
 */
public final class RegisteredWorkerPreparation
        implements WorkerPreparation {

    private final String workerGroupId;
    private final WorkerTransportType transportType;
    private final WorkerIdentityStore identityStore;
    private final WorkerPropertiesProvider propertiesProvider;
    private final WorkerControlClient controlClient;
    private final Duration requestTimeout;
    private boolean closed;

    public RegisteredWorkerPreparation(
            String workerGroupId,
            WorkerTransportType transportType,
            WorkerIdentityStore identityStore,
            WorkerPropertiesProvider propertiesProvider,
            WorkerControlClient controlClient,
            Duration requestTimeout
    ) {
        this.workerGroupId = requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        this.transportType = Objects.requireNonNull(
                transportType,
                "transportType"
        );
        this.identityStore = Objects.requireNonNull(
                identityStore,
                "identityStore"
        );
        this.propertiesProvider = Objects.requireNonNull(
                propertiesProvider,
                "propertiesProvider"
        );
        this.controlClient = Objects.requireNonNull(
                controlClient,
                "controlClient"
        );
        this.requestTimeout = requirePositive(
                requestTimeout,
                "requestTimeout"
        );
    }

    @Override
    public synchronized PreparedWorker prepare() throws Exception {
        requireOpen();
        WorkerPropertiesSnapshot properties = WorkerPropertiesSnapshot.from(
                propertiesProvider.loadProperties()
        );
        Optional<String> cached = identityStore.loadWorkerId();
        String workerId;
        if (cached.isPresent()) {
            workerId = requireWorkerId(cached.get());
        } else {
            workerId = requireWorkerId(controlClient.register(
                    workerGroupId,
                    properties.properties(),
                    requestTimeout
            ));
            persistWorkerId(workerId);
        }
        URI endpointUri = controlClient.bind(
                workerGroupId,
                workerId,
                transportType,
                properties.properties(),
                requestTimeout
        );
        return new PreparedWorker(workerId, endpointUri);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        controlClient.close();
    }

    private void persistWorkerId(String workerId) throws IOException {
        try {
            identityStore.saveWorkerId(workerId);
        } catch (IOException error) {
            throw new IOException("Unable to persist workerId", error);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "RegisteredWorkerPreparation is closed"
            );
        }
    }

    private static String requireWorkerId(String value) {
        return new WorkerConnectionBind(value).workerId();
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
