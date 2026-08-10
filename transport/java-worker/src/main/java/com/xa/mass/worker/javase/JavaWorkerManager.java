package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs one fixed set of replicas for one Java WorkerGroup.
 *
 * <p>Every replica shares this Manager's platform resources, event capacity,
 * and run policy. Reconciliation happens only when the Host explicitly
 * invokes it.
 */
public final class JavaWorkerManager implements AutoCloseable {

    private final LinkedHashMap<String, WorkerLifecycle> replicas;
    private final JavaWorkerPlatform platform;

    private boolean desiredRunning;
    private boolean closed;

    private JavaWorkerManager(
            LinkedHashMap<String, WorkerLifecycle> replicas,
            JavaWorkerPlatform platform
    ) {
        this.replicas = new LinkedHashMap<>(replicas);
        this.platform = Objects.requireNonNull(
                platform,
                "platform"
        );
    }

    public static Builder builder(
            URI runtimeApiBaseUrl,
            String workerGroupId,
            WorkerTransportType transportType
    ) {
        return new Builder(
                runtimeApiBaseUrl,
                workerGroupId,
                transportType
        );
    }

    public synchronized void start() {
        ensureOpen();
        desiredRunning = true;
        throwIfPresent(reconcileLocked());
    }

    public synchronized void stop() {
        ensureOpen();
        desiredRunning = false;
        throwIfPresent(reconcileLocked());
    }

    public synchronized void reconcile() {
        ensureOpen();
        throwIfPresent(reconcileLocked());
    }

    public synchronized WorkerLifecycle.Snapshot snapshot(
            String clientWorkerKey
    ) {
        return requireReplica(clientWorkerKey).snapshot();
    }

    public synchronized Map<String, WorkerLifecycle.Snapshot> snapshots() {
        Map<String, WorkerLifecycle.Snapshot> snapshots =
                new LinkedHashMap<>();
        replicas.forEach((clientWorkerKey, worker) -> snapshots.put(
                clientWorkerKey,
                worker.snapshot()
        ));
        return Collections.unmodifiableMap(snapshots);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        desiredRunning = false;

        List<WorkerLifecycle> closing =
                new ArrayList<>(replicas.values());
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (WorkerLifecycle worker : closing) {
            try {
                worker.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        try {
            platform.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        throwIfPresent(failure);
    }

    private RuntimeException reconcileLocked() {
        RuntimeException failure = null;
        for (Map.Entry<String, WorkerLifecycle> replica
                : replicas.entrySet()) {
            WorkerLifecycle worker = replica.getValue();
            try {
                WorkerLifecycle.State actual = worker.snapshot().state();
                if (desiredRunning
                        && actual == WorkerLifecycle.State.STOPPED) {
                    worker.start();
                } else if (!desiredRunning
                        && actual == WorkerLifecycle.State.RUNNING) {
                    worker.stop();
                }
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private WorkerLifecycle requireReplica(String clientWorkerKey) {
        String key = requireNonBlank(clientWorkerKey, "clientWorkerKey");
        WorkerLifecycle worker = replicas.get(key);
        if (worker == null) {
            throw new IllegalArgumentException(
                    "Unknown Java Worker replica: " + key
            );
        }
        return worker;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Java Worker manager is closed"
            );
        }
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static void throwIfPresent(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface WorkerAssembler {

        WorkerLifecycle assemble(
                JavaWorkerPlatform platform,
                String clientWorkerKey,
                WorkerIdentityStore identityStore,
                WorkerPropertiesProvider workerProperties
        );
    }

    public static final class Builder {

        private static final Duration DEFAULT_REQUEST_TIMEOUT =
                Duration.ofSeconds(10);

        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private final WorkerTransportType transportType;
        private final LinkedHashMap<String, ReplicaSpec> replicas =
                new LinkedHashMap<>();

        private List<WorkerEventDefinition<?>> definitions;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private TextMessageReconnectPolicy reconnectPolicy =
                TextMessageReconnectPolicy.defaults();
        private WorkerAssembler workerAssembler;

        private Builder(
                URI runtimeApiBaseUrl,
                String workerGroupId,
                WorkerTransportType transportType
        ) {
            this.runtimeApiBaseUrl = requireRuntimeApiBaseUrl(
                    runtimeApiBaseUrl
            );
            this.workerGroupId = requireNonBlank(
                    workerGroupId,
                    "workerGroupId"
            );
            this.transportType = requireTextMessageTransportType(
                    transportType
            );
        }

        public Builder eventDefinitions(
                Collection<? extends WorkerEventDefinition<?>> value
        ) {
            Objects.requireNonNull(value, "eventDefinitions");
            List<WorkerEventDefinition<?>> copy = new ArrayList<>();
            for (WorkerEventDefinition<?> definition : value) {
                copy.add(Objects.requireNonNull(
                        definition,
                        "eventDefinition"
                ));
            }
            definitions = Collections.unmodifiableList(copy);
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = requirePositive(value, "requestTimeout");
            return this;
        }

        public Builder reconnectPolicy(
                TextMessageReconnectPolicy value
        ) {
            reconnectPolicy = Objects.requireNonNull(
                    value,
                    "reconnectPolicy"
            );
            return this;
        }

        public Builder replica(
                String clientWorkerKey,
                WorkerIdentityStore identityStore,
                WorkerPropertiesProvider workerProperties
        ) {
            String key = requireNonBlank(
                    clientWorkerKey,
                    "clientWorkerKey"
            );
            if (replicas.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Duplicate Java Worker replica: " + key
                );
            }
            replicas.put(key, new ReplicaSpec(
                    key,
                    Objects.requireNonNull(identityStore, "identityStore"),
                    Objects.requireNonNull(
                            workerProperties,
                            "workerProperties"
                    )
            ));
            return this;
        }

        public JavaWorkerManager build() {
            if (definitions == null || definitions.isEmpty()) {
                throw new IllegalStateException(
                        "eventDefinitions must not be empty"
                );
            }
            if (replicas.isEmpty()) {
                throw new IllegalStateException(
                        "at least one Worker replica must be configured"
                );
            }

            LinkedHashMap<String, WorkerLifecycle> assembled =
                    new LinkedHashMap<>();
            JavaWorkerPlatform platform = JavaWorkerPlatform.managed(
                    workerGroupId,
                    replicas.size()
            );
            try {
                for (ReplicaSpec replica : replicas.values()) {
                    WorkerLifecycle worker = workerAssembler == null
                            ? JavaWorkerAssembly.assemble(
                                    runtimeApiBaseUrl,
                                    workerGroupId,
                                    replica.clientWorkerKey,
                                    transportType,
                                    replica.identityStore,
                                    replica.workerProperties,
                                    definitions,
                                    requestTimeout,
                                    reconnectPolicy,
                                    platform
                            )
                            : workerAssembler.assemble(
                                    platform,
                                    replica.clientWorkerKey,
                                    replica.identityStore,
                                    replica.workerProperties
                            );
                    if (worker == null) {
                        throw new IllegalStateException(
                                "Java Worker assembler returned null"
                        );
                    }
                    assembled.put(replica.clientWorkerKey, worker);
                }
                return new JavaWorkerManager(
                        assembled,
                        platform
                );
            } catch (RuntimeException | Error failure) {
                closeAssembledAndSuppress(assembled, failure);
                try {
                    platform.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        Builder workerAssembler(WorkerAssembler value) {
            workerAssembler = Objects.requireNonNull(
                    value,
                    "workerAssembler"
            );
            return this;
        }

        private static void closeAssembledAndSuppress(
                LinkedHashMap<String, WorkerLifecycle> assembled,
                Throwable failure
        ) {
            List<WorkerLifecycle> closing =
                    new ArrayList<>(assembled.values());
            Collections.reverse(closing);
            for (WorkerLifecycle worker : closing) {
                try {
                    worker.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static final class ReplicaSpec {

        private final String clientWorkerKey;
        private final WorkerIdentityStore identityStore;
        private final WorkerPropertiesProvider workerProperties;

        private ReplicaSpec(
                String clientWorkerKey,
                WorkerIdentityStore identityStore,
                WorkerPropertiesProvider workerProperties
        ) {
            this.clientWorkerKey = clientWorkerKey;
            this.identityStore = identityStore;
            this.workerProperties = workerProperties;
        }
    }

    private static URI requireRuntimeApiBaseUrl(URI value) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || (!("http".equalsIgnoreCase(value.getScheme()))
                && !("https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    "runtimeApiBaseUrl must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()
                || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static WorkerTransportType requireTextMessageTransportType(
            WorkerTransportType value
    ) {
        if (value != WorkerTransportType.WEBSOCKET
                && value != WorkerTransportType.SOCKET) {
            throw new IllegalArgumentException(
                    "transportType must be WEBSOCKET or SOCKET"
            );
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
        return value;
    }
}
