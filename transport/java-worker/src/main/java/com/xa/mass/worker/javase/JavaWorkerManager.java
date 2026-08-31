package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.worker.runtime.WorkerRunController;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
    private final JavaOkHttpWorkerControlClient batchControlClient;
    private final String workerGroupId;
    private final WorkerTransportType transportType;
    private final Duration requestTimeout;
    private final String batchWorkerKind;

    private final AtomicBoolean closed = new AtomicBoolean();

    private JavaWorkerManager(
            LinkedHashMap<String, ManagedReplica> replicas,
            JavaWorkerPlatform platform,
            JavaOkHttpWorkerControlClient batchControlClient,
            String workerGroupId,
            WorkerTransportType transportType,
            Duration requestTimeout,
            String batchWorkerKind
    ) {
        this.replicas = new LinkedHashMap<>(replicas);
        this.platform = Objects.requireNonNull(
                platform,
                "platform"
        );
        this.batchControlClient = batchControlClient;
        this.workerGroupId = workerGroupId;
        this.transportType = transportType;
        this.requestTimeout = requestTimeout;
        this.batchWorkerKind = batchWorkerKind;
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
        if (batchWorkerKind != null) {
            prepareAndStart(replicas.keySet());
            return;
        }
        RuntimeException failure = null;
        for (Map.Entry<String, ManagedReplica> entry
                : replicas.entrySet()) {
            try {
                failure = accumulateNullable(
                        failure,
                        requestStartAndReconcile(
                                entry.getKey(),
                                entry.getValue()
                        )
                );
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        throwIfPresent(failure);
    }

    public void start(String replicaKey) {
        ensureOpen();
        if (batchWorkerKind != null) {
            prepareAndStart(List.of(replicaKey));
            return;
        }
        ManagedReplica replica = requireReplica(replicaKey);
        throwIfPresent(requestStartAndReconcile(replicaKey, replica));
    }

    /**
     * Prepares the selected stopped replicas in one control request and starts
     * each returned runtime coordinate without invoking per-replica prepare.
     */
    public void prepareAndStart(
            Collection<String> replicaKeys
    ) {
        prepareAndStart(replicaKeys, true);
    }

    private void prepareAndStart(
            Collection<String> replicaKeys,
            boolean requestRunning
    ) {
        ensureOpen();
        if (batchWorkerKind == null) {
            throw new IllegalStateException(
                    "Java Worker manager has no batch worker kind"
            );
        }
        List<ManagedReplica> targets = selectBatchTargets(
                replicaKeys,
                requestRunning
        );
        if (targets.isEmpty()) {
            return;
        }
        List<Map<String, Object>> properties = new ArrayList<>();
        for (ManagedReplica target : targets) {
            properties.add(loadBatchProperties(target));
        }

        List<PreparedWorker> prepared;
        try {
            prepared = Objects.requireNonNull(
                    batchControlClient,
                    "batchControlClient"
            ).prepareBatch(
                    batchWorkerKind,
                    workerGroupId,
                    transportType,
                    properties,
                    requestTimeout
            );
        } catch (IOException error) {
            throw new WorkerException(
                    WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE,
                    "workerControl.prepareBatch",
                    "Worker batch preparation is unavailable",
                    error
            );
        }

        for (int index = 0; index < targets.size(); index++) {
            installPreparedWorker(
                    targets.get(index),
                    prepared.get(index)
            );
        }
    }

    public void stop() {
        ensureOpen();
        RuntimeException failure = null;
        for (ManagedReplica replica : replicas.values()) {
            failure = accumulateNullable(
                    failure,
                    requestStopAndReconcile(replica)
            );
        }
        throwIfPresent(failure);
    }

    public void stop(String replicaKey) {
        ensureOpen();
        ManagedReplica replica = requireReplica(replicaKey);
        throwIfPresent(requestStopAndReconcile(replica));
    }

    public void reconcile() {
        ensureOpen();
        if (batchWorkerKind != null) {
            List<String> stoppedDesired = stoppedDesiredReplicaKeys();
            if (!stoppedDesired.isEmpty()) {
                prepareAndStart(stoppedDesired, false);
            }
            reconcileStops();
            return;
        }
        RuntimeException failure = null;
        for (ManagedReplica replica : replicas.values()) {
            failure = accumulateNullable(
                    failure,
                    reconcileReplica(replica)
            );
        }
        throwIfPresent(failure);
    }

    public void reconcile(String replicaKey) {
        ensureOpen();
        ManagedReplica replica = requireReplica(replicaKey);
        if (batchWorkerKind != null
                && replica.desiredRunning.get()
                && replica.worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED) {
            prepareAndStart(List.of(replicaKey), false);
            return;
        }
        throwIfPresent(reconcileReplica(replica));
    }

    public boolean desiredRunning(String replicaKey) {
        ensureOpen();
        return requireReplica(replicaKey).desiredRunning.get();
    }

    public WorkerLifecycle.Snapshot snapshot(
            String replicaKey
    ) {
        return requireReplica(replicaKey).worker.snapshot();
    }

    public Map<String, WorkerLifecycle.Snapshot> snapshots() {
        Map<String, WorkerLifecycle.Snapshot> snapshots =
                new LinkedHashMap<>();
        replicas.forEach((replicaKey, replica) -> snapshots.put(
                replicaKey,
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
        if (batchControlClient != null) {
            try {
                batchControlClient.close();
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

    private RuntimeException requestStartAndReconcile(
            String replicaKey,
            ManagedReplica replica
    ) {
        requestStart(replicaKey, replica);
        return reconcile(replica);
    }

    private RuntimeException requestStopAndReconcile(
            ManagedReplica replica
    ) {
        replica.desiredRunning.set(false);
        return reconcile(replica);
    }

    private RuntimeException reconcileReplica(ManagedReplica replica) {
        return reconcile(replica);
    }

    private void requestStart(
            String replicaKey,
            ManagedReplica replica
    ) {
        if (!replica.desiredRunning.get()
                && replica.worker.snapshot().state()
                == WorkerLifecycle.State.RUNNING) {
            throw new IllegalStateException(
                    "Java Worker replica is still stopping: "
                            + replicaKey
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

    private ManagedReplica requireReplica(String replicaKey) {
        String key = requireNonBlank(replicaKey, "replicaKey");
        ManagedReplica replica = replicas.get(key);
        if (replica == null) {
            throw new IllegalArgumentException(
                    "Unknown Java Worker replica: " + key
            );
        }
        return replica;
    }

    private List<ManagedReplica> selectBatchTargets(
            Collection<String> replicaKeys,
            boolean requestRunning
    ) {
        if (replicaKeys == null
                || replicaKeys.isEmpty()
                || replicaKeys.size() > 100) {
            throw new IllegalArgumentException(
                    "replicaKeys must contain 1..100 entries"
            );
        }
        List<ManagedReplica> requested = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String rawKey : replicaKeys) {
            String key = requireNonBlank(rawKey, "replicaKey");
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                        "replicaKeys must be unique"
                );
            }
            ManagedReplica replica = requireReplica(key);
            requested.add(replica);
        }

        if (requestRunning) {
            for (ManagedReplica replica : requested) {
                if (!replica.desiredRunning.get()
                        && replica.worker.snapshot().state()
                        == WorkerLifecycle.State.RUNNING) {
                    throw new IllegalStateException(
                            "Java Worker replica is still stopping: "
                            + replica.replicaKey
                    );
                }
            }
            for (ManagedReplica replica : requested) {
                replica.desiredRunning.set(true);
            }
        }

        List<ManagedReplica> targets = new ArrayList<>();
        for (ManagedReplica replica : requested) {
            if (replica.desiredRunning.get()
                    && replica.worker.snapshot().state()
                    == WorkerLifecycle.State.STOPPED) {
                targets.add(replica);
            }
        }
        return targets;
    }

    private void installPreparedWorker(
            ManagedReplica target,
            PreparedWorker preparedWorker
    ) {
        if (closed.get()
                || !target.desiredRunning.get()
                || target.worker.snapshot().state()
                != WorkerLifecycle.State.STOPPED) {
            return;
        }
        target.preparedStarter.start(preparedWorker);
        throwIfPresent(reconcile(target));
    }

    private static Map<String, Object> loadBatchProperties(
            ManagedReplica target
    ) {
        try {
            return target.batchProperties.loadProperties();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new WorkerException(
                    WorkerErrorCode.WORKER_CONTROL_RESPONSE_INVALID,
                    "workerControl.prepareBatch",
                    "Worker properties could not be loaded",
                    error
            );
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "Java Worker manager is closed"
            );
        }
    }

    private List<String> stoppedDesiredReplicaKeys() {
        List<String> keys = new ArrayList<>();
        replicas.forEach((key, replica) -> {
            if (replica.desiredRunning.get()
                    && replica.worker.snapshot().state()
                    == WorkerLifecycle.State.STOPPED) {
                keys.add(key);
            }
        });
        return keys;
    }

    private void reconcileStops() {
        RuntimeException failure = null;
        for (ManagedReplica replica : replicas.values()) {
            if (!replica.desiredRunning.get()) {
                failure = accumulateNullable(
                        failure,
                        reconcileReplica(replica)
                );
            }
        }
        throwIfPresent(failure);
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
                String replicaKey,
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
        private String batchWorkerKind;
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

        public Builder batchWorkerKind(String value) {
            batchWorkerKind = requireNonBlank(value, "batchWorkerKind");
            return this;
        }

        public Builder replica(
                String replicaKey,
                WorkerPropertiesProvider workerProperties
        ) {
            String key = requireNonBlank(
                    replicaKey,
                    "replicaKey"
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

            LinkedHashMap<String, ManagedReplica> assembled =
                    new LinkedHashMap<>();
            JavaWorkerPlatform platform = JavaWorkerPlatform.managed(
                    workerGroupId,
                    replicas.size()
            );
            JavaOkHttpWorkerControlClient batchControlClient = null;
            try {
                if (batchWorkerKind != null) {
                    batchControlClient = platform.batchControlClient(
                            runtimeApiBaseUrl
                    );
                }
                for (ReplicaSpec replica : replicas.values()) {
                    WorkerPropertiesProvider batchProperties =
                            JavaWorkerProperties.snapshotProvider(
                                    replica.workerProperties
                            );
                    WorkerPropertiesProvider completeProperties =
                            JavaWorkerProperties.completeProvider(
                                    replica.replicaKey,
                                    replica.workerProperties
                            );
                    WorkerLifecycle worker;
                    PreparedStarter preparedStarter;
                    if (workerAssembler == null) {
                        WorkerRunController controller =
                                JavaWorkerAssembly.assembleComplete(
                                    runtimeApiBaseUrl,
                                    workerGroupId,
                                    transportType,
                                    replica.workerProperties,
                                    completeProperties,
                                    definitionExtensions,
                                    options,
                                    platform
                            );
                        worker = controller;
                        preparedStarter = controller::start;
                    } else {
                        worker = workerAssembler.assemble(
                                    platform,
                                    replica.replicaKey,
                                    replica.workerProperties
                            );
                        preparedStarter = prepared -> {
                            throw new UnsupportedOperationException(
                                    "Custom Worker assembler does not support "
                                            + "batch preparation"
                            );
                        };
                    }
                    if (worker == null) {
                        throw new IllegalStateException(
                                "Java Worker assembler returned null"
                        );
                    }
                    assembled.put(
                            replica.replicaKey,
                            new ManagedReplica(
                                    replica.replicaKey,
                                    worker,
                                    batchProperties,
                                    preparedStarter
                            )
                    );
                }
                return new JavaWorkerManager(
                        assembled,
                        platform,
                        batchControlClient,
                        workerGroupId,
                        transportType,
                        options.requestTimeout(),
                        batchWorkerKind
                );
            } catch (RuntimeException | Error failure) {
                closeAssembledAndSuppress(assembled, failure);
                if (batchControlClient != null) {
                    try {
                        batchControlClient.close();
                    } catch (RuntimeException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
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
                LinkedHashMap<String, ManagedReplica> assembled,
                Throwable failure
        ) {
            List<ManagedReplica> closing =
                    new ArrayList<>(assembled.values());
            Collections.reverse(closing);
            for (ManagedReplica replica : closing) {
                try {
                    replica.worker.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static final class ReplicaSpec {

        private final String replicaKey;
        private final WorkerPropertiesProvider workerProperties;

        private ReplicaSpec(
                String replicaKey,
                WorkerPropertiesProvider workerProperties
        ) {
            this.replicaKey = replicaKey;
            this.workerProperties = workerProperties;
        }
    }

    private static final class ManagedReplica {

        private final String replicaKey;
        private final WorkerLifecycle worker;
        private final WorkerPropertiesProvider batchProperties;
        private final PreparedStarter preparedStarter;
        private final AtomicBoolean desiredRunning = new AtomicBoolean();

        private ManagedReplica(
                String replicaKey,
                WorkerLifecycle worker,
                WorkerPropertiesProvider batchProperties,
                PreparedStarter preparedStarter
        ) {
            this.replicaKey = Objects.requireNonNull(
                    replicaKey,
                    "replicaKey"
            );
            this.worker = Objects.requireNonNull(worker, "worker");
            this.batchProperties = Objects.requireNonNull(
                    batchProperties,
                    "batchProperties"
            );
            this.preparedStarter = Objects.requireNonNull(
                    preparedStarter,
                    "preparedStarter"
            );
        }
    }

    @FunctionalInterface
    private interface PreparedStarter {

        void start(PreparedWorker preparedWorker);
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
