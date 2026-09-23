package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads complete Worker Properties and performs one control preparation. */
public final class WorkerControlPreparation implements WorkerPreparation {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";

    private final String workerGroupId;
    private final WorkerTransportType transportType;
    private final WorkerPropertiesProvider propertiesProvider;
    private final WorkerControlClient controlClient;
    private final Duration requestTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkerControlPreparation(
            String workerGroupId,
            WorkerTransportType transportType,
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
    public PreparedWorker prepare() throws Exception {
        requireOpen();
        Map<String, String> properties = immutableProperties(
                propertiesProvider.loadProperties()
        );
        return Objects.requireNonNull(
                controlClient.prepare(
                        workerGroupId,
                        transportType,
                        properties,
                        requestTimeout
                ),
                "preparedWorker"
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        controlClient.close();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "WorkerControlPreparation is closed"
            );
        }
    }

    private static Map<String, String> immutableProperties(
            Map<?, ?> source
    ) {
        Map<String, String> properties = WorkerDeliveryCodec.copyWorkerProperties(source);
        String clientKey = properties.get(CLIENT_WORKER_KEY);
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException(
                    "workerProperties.clientWorkerKey must be a "
                            + "non-blank string"
            );
        }
        return properties;
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
