package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one fixed set of replicas for one Java WorkerGroup.
 *
 * <p>Every replica shares this Manager's platform resources, Definition
 * extensions, and connection options. Reconciliation happens only when the
 * Host explicitly invokes it.
 */
public final class JavaWorkerManager implements AutoCloseable {

    private final LinkedHashMap<String, ManagedReplica> replicas;
    private final JavaWorkerPlatform platform;

    private final AtomicBoolean closed = new AtomicBoolean();

    private JavaWorkerManager(
            LinkedHashMap<String, WorkerLifecycle> replicas,
            JavaWorkerPlatform platform
    ) {
        this.replicas = new LinkedHashMap<>();
        replicas.forEach((clientWorkerKey, worker) -> this.replicas.put(
                clientWorkerKey,
                new ManagedReplica(worker)
        ));
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

    public void start() {
        ensureOpen();
        RuntimeException failure = null;
        for (Map.Entry<String, ManagedReplica> entry
                : replicas.entrySet()) {
            try {
                requestStart(entry.getKey(), entry.getValue());
                failure = accumulateNullable(
                        failure,
                        reconcile(entry.getValue())
                );
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        throwIfPresent(failure);
    }

    public void start(String clientWorkerKey) {
        ensureOpen();
        ManagedReplica replica = requireReplica(clientWorkerKey);
        requestStart(clientWorkerKey, replica);
        throwIfPresent(reconcile(replica));
    }

    public void stop() {
        ensureOpen();
        RuntimeException failure = null;
        for (ManagedReplica replica : replicas.values()) {
            replica.desiredRunning.set(false);
            failure = accumulateNullable(failure, reconcile(replica));
        }
        throwIfPresent(failure);
    }

    public void stop(String clientWorkerKey) {
        ensureOpen();
        ManagedReplica replica = requireReplica(clientWorkerKey);
        replica.desiredRunning.set(false);
        throwIfPresent(reconcile(replica));
    }

    public void reconcile() {
        ensureOpen();
        RuntimeException failure = null;
        for (ManagedReplica replica : replicas.values()) {
            failure = accumulateNullable(failure, reconcile(replica));
        }
        throwIfPresent(failure);
    }

    public void reconcile(String clientWorkerKey) {
        ensureOpen();
        throwIfPresent(reconcile(requireReplica(clientWorkerKey)));
    }

    public boolean desiredRunning(String clientWorkerKey) {
        ensureOpen();
        return requireReplica(clientWorkerKey).desiredRunning.get();
    }

    public WorkerLifecycle.Snapshot snapshot(
            String clientWorkerKey
    ) {
        return requireReplica(clientWorkerKey).worker.snapshot();
    }

    public Map<String, WorkerLifecycle.Snapshot> snapshots() {
        Map<String, WorkerLifecycle.Snapshot> snapshots =
                new LinkedHashMap<>();
        replicas.forEach((clientWorkerKey, replica) -> snapshots.put(
                clientWorkerKey,
                replica.worker.snapshot()
        ));
        return Collections.unmodifiableMap(snapshots);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        replicas.values().forEach(
                replica -> replica.desiredRunning.set(false)
        );

        List<WorkerLifecycle> closing = new ArrayList<>();
        for (ManagedReplica replica : replicas.values()) {
            closing.add(replica.worker);
        }
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

    private void requestStart(
            String clientWorkerKey,
            ManagedReplica replica
    ) {
        if (!replica.desiredRunning.get()
                && replica.worker.snapshot().state()
                == WorkerLifecycle.State.RUNNING) {
            throw new IllegalStateException(
                    "Java Worker replica is still stopping: "
                            + clientWorkerKey
            );
        }
        replica.desiredRunning.set(true);
    }

    private RuntimeException reconcile(ManagedReplica replica) {
        RuntimeException failure = null;
        boolean desired = replica.desiredRunning.get();
        while (true) {
            try {
                WorkerLifecycle.State actual =
                        replica.worker.snapshot().state();
                if (desired
                        && actual == WorkerLifecycle.State.STOPPED) {
                    replica.worker.start();
                } else if (!desired
                        && actual == WorkerLifecycle.State.RUNNING) {
                    replica.worker.stop();
                }
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }

            boolean latest = replica.desiredRunning.get();
            if (latest == desired) {
                return failure;
            }
            desired = latest;
        }
    }

    private ManagedReplica requireReplica(String clientWorkerKey) {
        String key = requireNonBlank(clientWorkerKey, "clientWorkerKey");
        ManagedReplica replica = replicas.get(key);
        if (replica == null) {
            throw new IllegalArgumentException(
                    "Unknown Java Worker replica: " + key
            );
        }
        return replica;
    }

    private void ensureOpen() {
        if (closed.get()) {
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

    private static RuntimeException accumulateNullable(
            RuntimeException current,
            RuntimeException addition
    ) {
        return addition == null ? current : accumulate(current, addition);
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
                WorkerPropertiesProvider workerProperties
        );
    }

    public static final class Builder {

        private final URI runtimeApiBaseUrl;
        private final String workerGroupId;
        private final WorkerTransportType transportType;
        private final LinkedHashMap<String, ReplicaSpec> replicas =
                new LinkedHashMap<>();

        private final List<WorkerEventDefinition<?>> definitionExtensions =
                new ArrayList<>();
        private WorkerConnectionOptions options =
                WorkerConnectionOptions.defaults();
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

        public Builder extendEventDefinitions(
                Collection<? extends WorkerEventDefinition<?>> value
        ) {
            Objects.requireNonNull(value, "definitionExtensions");
            for (WorkerEventDefinition<?> definition : value) {
                definitionExtensions.add(Objects.requireNonNull(
                        definition,
                        "definitionExtension"
                ));
            }
            return this;
        }

        public Builder options(WorkerConnectionOptions value) {
            options = Objects.requireNonNull(
                    value,
                    "options"
            );
            return this;
        }

        public Builder replica(
                String clientWorkerKey,
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
                    Objects.requireNonNull(
                            workerProperties,
                            "workerProperties"
                    )
            ));
            return this;
        }

        public JavaWorkerManager build() {
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
                                    replica.workerProperties,
                                    definitionExtensions,
                                    options,
                                    platform
                            )
                            : workerAssembler.assemble(
                                    platform,
                                    replica.clientWorkerKey,
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
        private final WorkerPropertiesProvider workerProperties;

        private ReplicaSpec(
                String clientWorkerKey,
                WorkerPropertiesProvider workerProperties
        ) {
            this.clientWorkerKey = clientWorkerKey;
            this.workerProperties = workerProperties;
        }
    }

    private static final class ManagedReplica {

        private final WorkerLifecycle worker;
        private final AtomicBoolean desiredRunning = new AtomicBoolean();

        private ManagedReplica(WorkerLifecycle worker) {
            this.worker = Objects.requireNonNull(worker, "worker");
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
