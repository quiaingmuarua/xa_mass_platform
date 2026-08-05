package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScenarioWorkers implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(
            ScenarioWorkers.class.getName()
    );
    private static final int WORKER_START_FAILED = 14004;
    private static final int WORKER_CONNECT_TIMEOUT = 14005;
    private static final int WORKER_INDEX_FAILED = 14010;

    private final List<GroupAssembly> groups;
    private final ScenarioWorkerResourceClient resourceClient;
    private final WorkerFactory workerFactory;
    private final List<WorkerHandle> workers = new ArrayList<>();
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            ScenarioWorkerResourceClient resourceClient,
            WorkerFactory workerFactory
    ) {
        this.groups = resolveGroups(
                configs,
                immutableDefinitions(definitionsByEventCode)
        );
        this.resourceClient = Objects.requireNonNull(
                resourceClient,
                "resourceClient"
        );
        this.workerFactory = Objects.requireNonNull(
                workerFactory,
                "workerFactory"
        );
    }

    public static ScenarioWorkers fromJson(
            String workerConfigJson,
            URI runtimeApiBaseUrl
    ) {
        try {
            return new ScenarioWorkers(
                    ScenarioWorkersJsonParser.parse(workerConfigJson),
                    builtInDefinitions(),
                    new HttpScenarioWorkerResourceClient(
                            runtimeApiBaseUrl
                    ),
                    ScenarioWorkers::createWorker
            );
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.parseConfig",
                    "Scenario Worker configuration is invalid: "
                            + error.getMessage(),
                    error
            );
        }
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Scenario Workers are closed");
        }
        if (started) {
            return;
        }

        try {
            startTransports();
            awaitConnections();
            registerResources();
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            closeWorkersAndSuppress(failure);
            if (failure instanceof ScenarioWorkerAssemblyException) {
                throw failure;
            }
            throw new ScenarioWorkerAssemblyException(
                    WORKER_START_FAILED,
                    "scenarioWorkers.start",
                    "Could not start Scenario Workers",
                    failure
            );
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = closeWorkers();
        if (failure != null) {
            throw failure;
        }
    }

    private void startTransports() {
        for (GroupAssembly group : groups) {
            for (ScenarioWorkerConfig worker : group.config().workers()) {
                WebSocketWorkerTransport transport = workerFactory.create(
                        worker.workerId(),
                        group.config(),
                        group.definitions()
                );
                workers.add(new WorkerHandle(
                        group.config(),
                        worker,
                        transport
                ));
                transport.start();
            }
        }
    }

    private void awaitConnections() {
        for (GroupAssembly group : groups) {
            long deadline = System.nanoTime()
                    + group.config().connectTimeout().toNanos();
            List<WorkerHandle> groupWorkers = workers.stream()
                    .filter(worker -> worker.groupConfig() == group.config())
                    .toList();
            while (System.nanoTime() < deadline) {
                if (groupWorkers.stream().allMatch(
                        worker -> worker.transport().isConnected()
                )) {
                    break;
                }
                sleepForConnection(group.config().workerGroupId());
            }
            long connected = groupWorkers.stream()
                    .filter(worker -> worker.transport().isConnected())
                    .count();
            if (connected != groupWorkers.size()) {
                throw new ScenarioWorkerAssemblyException(
                        WORKER_CONNECT_TIMEOUT,
                        "scenarioWorkers.awaitConnections",
                        "Only "
                                + connected
                                + " of "
                                + groupWorkers.size()
                                + " Workers connected for WorkerGroup "
                                + group.config().workerGroupId()
                );
            }
        }
    }

    private void registerResources() {
        for (WorkerHandle handle : workers) {
            ScenarioWorkerGroupConfig group = handle.groupConfig();
            ScenarioWorkerConfig worker = handle.workerConfig();
            resourceClient.registerWorker(
                    group.workerGroupId(),
                    worker.workerId(),
                    group.endpointManagerId(),
                    worker.workerProperties(),
                    group.requestTimeout()
            );
            resourceClient.updateWorkerProperties(
                    group.workerGroupId(),
                    worker.workerId(),
                    worker.workerProperties(),
                    group.requestTimeout()
            );
            updateIndexes(group, worker);
        }
    }

    private void updateIndexes(
            ScenarioWorkerGroupConfig group,
            ScenarioWorkerConfig worker
    ) {
        if (worker.indexedPropertyUpdates().isEmpty()) {
            return;
        }
        try {
            Map<String, ScenarioWorkerResourceResult> results =
                    resourceClient.updateIndexedProperties(
                            group.workerGroupId(),
                            worker.workerId(),
                            worker.indexedPropertyUpdates(),
                            group.requestTimeout()
                    );
            worker.indexedPropertyUpdates().keySet().forEach(field -> {
                ScenarioWorkerResourceResult result = results.get(field);
                if (result == null || !result.accepted()) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "errorCode=" + WORKER_INDEX_FAILED
                                    + " operation=workerPropertyIndex.update"
                                    + " workerGroupId="
                                    + group.workerGroupId()
                                    + " workerId=" + worker.workerId()
                                    + " field=" + field
                                    + " status=" + (result == null
                                    ? "missing"
                                    : result.status())
                    );
                }
            });
        } catch (RuntimeException error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "errorCode=" + WORKER_INDEX_FAILED
                            + " operation=workerPropertyIndex.update"
                            + " workerGroupId=" + group.workerGroupId()
                            + " workerId=" + worker.workerId()
                            + " errorType="
                            + error.getClass().getSimpleName()
            );
        }
    }

    private static void sleepForConnection(String workerGroupId) {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScenarioWorkerAssemblyException(
                    WORKER_START_FAILED,
                    "scenarioWorkers.awaitConnections",
                    "Interrupted while waiting for WorkerGroup "
                            + workerGroupId,
                    error
            );
        }
    }

    private RuntimeException closeWorkers() {
        List<WorkerHandle> closing = new ArrayList<>(workers);
        workers.clear();
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (WorkerHandle worker : closing) {
            try {
                worker.transport().close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        return failure;
    }

    private void closeWorkersAndSuppress(RuntimeException failure) {
        RuntimeException closeFailure = closeWorkers();
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static WebSocketWorkerTransport createWorker(
            String workerId,
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
    ) {
        return new WebSocketWorkerTransport(
                new OkHttpTextWebSocketClient(
                        config.workerWebSocketUri(),
                        config.requestTimeout(),
                        config.reconnectInterval()
                ),
                workerId,
                definitions
        );
    }

    private static Map<String, WorkerEventDefinition<?>>
    builtInDefinitions() {
        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        addDefinitions(definitions, PhoneNumberWorkerEvents.definitions());
        addDefinitions(definitions, StringUtilityWorkerEvents.definitions());
        return Collections.unmodifiableMap(definitions);
    }

    private static void addDefinitions(
            Map<String, WorkerEventDefinition<?>> target,
            List<WorkerEventDefinition<?>> definitions
    ) {
        for (WorkerEventDefinition<?> definition : definitions) {
            WorkerEventDefinition<?> existing = target.putIfAbsent(
                    definition.eventCode(),
                    definition
            );
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate Scenario Worker eventCode: "
                                + definition.eventCode()
                );
            }
        }
    }

    private static Map<String, WorkerEventDefinition<?>>
    immutableDefinitions(
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode
    ) {
        Objects.requireNonNull(
                definitionsByEventCode,
                "definitionsByEventCode"
        );
        Map<String, WorkerEventDefinition<?>> copy = new LinkedHashMap<>();
        definitionsByEventCode.forEach((eventCode, definition) -> {
            if (eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Definition eventCode key must be non-blank"
                );
            }
            if (definition == null
                    || !eventCode.equals(definition.eventCode())) {
                throw new IllegalArgumentException(
                        "Definition key does not match eventCode: "
                                + eventCode
                );
            }
            if (!WorkerMessageEndpoint.TASK.wireValue()
                    .equals(definition.src())) {
                throw new IllegalArgumentException(
                        "Scenario Definition src must be TASK: "
                                + eventCode
                );
            }
            copy.put(eventCode, definition);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static List<GroupAssembly> resolveGroups(
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode
    ) {
        Objects.requireNonNull(configs, "configs");
        List<GroupAssembly> groups = new ArrayList<>(configs.size());
        for (ScenarioWorkerGroupConfig config : configs) {
            List<WorkerEventDefinition<?>> definitions = new ArrayList<>();
            for (String eventCode : config.eventCodes()) {
                WorkerEventDefinition<?> definition =
                        definitionsByEventCode.get(eventCode);
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "WorkerGroup "
                                    + config.workerGroupId()
                                    + " references unknown eventCode "
                                    + eventCode
                    );
                }
                definitions.add(definition);
            }
            groups.add(new GroupAssembly(config, List.copyOf(definitions)));
        }
        return List.copyOf(groups);
    }

    @FunctionalInterface
    interface WorkerFactory {
        WebSocketWorkerTransport create(
                String workerId,
                ScenarioWorkerGroupConfig config,
                List<WorkerEventDefinition<?>> definitions
        );
    }

    private record GroupAssembly(
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
    ) {
    }

    private record WorkerHandle(
            ScenarioWorkerGroupConfig groupConfig,
            ScenarioWorkerConfig workerConfig,
            WebSocketWorkerTransport transport
    ) {
    }
}
