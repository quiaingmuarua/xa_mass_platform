package com.xa.mass.worker.runtime;

import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, Object> properties = immutableProperties(
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

    private static Map<String, Object> immutableProperties(
            Map<?, ?> source
    ) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        Map<String, Object> properties = immutableObject(source);
        Object rawClientKey = properties.get(CLIENT_WORKER_KEY);
        if (!(rawClientKey instanceof String)
                || ((String) rawClientKey).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerProperties.clientWorkerKey must be a "
                            + "non-blank string"
            );
        }
        return properties;
    }

    private static Map<String, Object> immutableObject(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "workerProperties keys must be strings"
                );
            }
            result.put(
                    (String) entry.getKey(),
                    immutableValue(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            return immutableObject((Map<?, ?>) value);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(immutableValue(item));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException(
                "workerProperties contain a non-JSON value"
        );
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
