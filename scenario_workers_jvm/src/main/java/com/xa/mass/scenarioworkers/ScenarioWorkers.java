package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
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
    private final WorkerControl workerControl;
    private final ScenarioWorkerIndexClient indexClient;
    private final WorkerFactory workerFactory;
    private final List<WorkerHandle> workers = new ArrayList<>();
    private final List<ScenarioWorkerSandbox> sandboxes =
            new ArrayList<>();
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            WorkerControl workerControl,
            ScenarioWorkerIndexClient indexClient,
            WorkerFactory workerFactory
    ) {
        groups = resolveGroups(
                configs,
                immutableDefinitions(definitionsByEventCode)
        );
        this.workerControl = Objects.requireNonNull(
                workerControl,
                "workerControl"
        );
        this.indexClient = Objects.requireNonNull(
                indexClient,
                "indexClient"
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
            OkHttpWorkerControlClient controlClient =
                    new OkHttpWorkerControlClient(runtimeApiBaseUrl);
            return new ScenarioWorkers(
                    ScenarioWorkersJsonParser.parse(workerConfigJson),
                    builtInDefinitions(),
                    new HttpWorkerControl(controlClient),
                    new HttpScenarioWorkerIndexClient(runtimeApiBaseUrl),
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
            List<PreparedWorker> preparedWorkers = prepareWorkers();
            registerBindAndStartTransports(preparedWorkers);
            awaitConnections();
            updateIndexes();
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            closeResourcesAndSuppress(failure);
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
        try {
            workerControl.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        failure = closeSandboxes(failure);
        if (failure != null) {
            throw failure;
        }
    }

    private List<PreparedWorker> prepareWorkers() {
        List<PreparedWorker> prepared = new ArrayList<>();
        for (GroupAssembly group : groups) {
            for (ScenarioWorkerConfig worker : group.config().workers()) {
                ScenarioWorkerSandbox sandbox = null;
                Map<String, Object> workerProperties =
                        worker.workerProperties();
                String workerId = null;
                if (worker.sandboxDirectory() != null) {
                    sandbox = ScenarioWorkerSandbox.open(
                            worker.sandboxDirectory(),
                            group.config().workerGroupId(),
                            worker.clientWorkerKey(),
                            worker.workerProperties()
                    );
                    sandboxes.add(sandbox);
                    workerProperties = sandbox.workerProperties();
                    workerId = sandbox.workerId().orElse(null);
                }
                prepared.add(new PreparedWorker(
                        group,
                        worker,
                        sandbox,
                        workerProperties,
                        workerId
                ));
            }
        }
        return List.copyOf(prepared);
    }

    private void registerBindAndStartTransports(
            List<PreparedWorker> preparedWorkers
    ) {
        for (PreparedWorker prepared : preparedWorkers) {
            GroupAssembly group = prepared.group();
            ScenarioWorkerConfig worker = prepared.workerConfig();
            String workerId = prepared.workerId();
            if (workerId == null) {
                workerId = workerControl.register(
                        group.config().workerGroupId(),
                        worker.clientWorkerKey(),
                        group.config().requestTimeout()
                );
                if (prepared.sandbox() != null) {
                    prepared.sandbox().storeWorkerId(workerId);
                }
            }
            URI endpointUri = workerControl.bind(
                    group.config().workerGroupId(),
                    worker.clientWorkerKey(),
                    workerId,
                    prepared.workerProperties(),
                    group.config().requestTimeout()
            );
            WebSocketWorkerTransport transport = workerFactory.create(
                    workerId,
                    endpointUri,
                    group.config(),
                    group.definitions()
            );
            workers.add(new WorkerHandle(
                    group.config(),
                    worker,
                    workerId,
                    transport
            ));
            transport.start();
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
                sleep(
                        Duration.ofMillis(50),
                        "scenarioWorkers.awaitConnections",
                        group.config().workerGroupId()
                );
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

    private void updateIndexes() {
        for (WorkerHandle handle : workers) {
            updateIndexes(handle);
        }
    }

    private void updateIndexes(WorkerHandle handle) {
        ScenarioWorkerConfig worker = handle.workerConfig();
        if (worker.indexedPropertyUpdates().isEmpty()) {
            return;
        }
        ScenarioWorkerGroupConfig group = handle.groupConfig();
        long deadline = System.nanoTime()
                + group.connectTimeout().toNanos();
        while (true) {
            Map<String, ScenarioWorkerIndexResult> results;
            try {
                results = indexClient.updateIndexedProperties(
                        group.workerGroupId(),
                        handle.workerId(),
                        worker.indexedPropertyUpdates(),
                        group.requestTimeout()
                );
            } catch (RuntimeException error) {
                logIndexFailure(group, handle.workerId(), null, error);
                return;
            }
            boolean retry = results.values().stream()
                    .anyMatch(ScenarioWorkerIndexResult::notFound)
                    && System.nanoTime() < deadline;
            if (retry) {
                sleep(
                        group.reconnectInterval(),
                        "scenarioWorkers.retryIndex",
                        group.workerGroupId()
                );
                continue;
            }
            worker.indexedPropertyUpdates().keySet().forEach(field -> {
                ScenarioWorkerIndexResult result = results.get(field);
                if (result == null || !result.accepted()) {
                    logIndexFailure(
                            group,
                            handle.workerId(),
                            field,
                            null
                    );
                }
            });
            return;
        }
    }

    private static void logIndexFailure(
            ScenarioWorkerGroupConfig group,
            String workerId,
            String field,
            RuntimeException error
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode=" + WORKER_INDEX_FAILED
                        + " operation=workerPropertyIndex.update"
                        + " workerGroupId=" + group.workerGroupId()
                        + " workerId=" + workerId
                        + (field == null ? "" : " field=" + field)
                        + (error == null
                        ? ""
                        : " errorType="
                                + error.getClass().getSimpleName())
        );
    }

    private static void sleep(
            Duration duration,
            String operation,
            String workerGroupId
    ) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScenarioWorkerAssemblyException(
                    WORKER_START_FAILED,
                    operation,
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
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private void closeResourcesAndSuppress(RuntimeException failure) {
        RuntimeException closeFailure = closeWorkers();
        try {
            workerControl.close();
        } catch (RuntimeException error) {
            closeFailure = accumulate(closeFailure, error);
        }
        closeFailure = closeSandboxes(closeFailure);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private RuntimeException closeSandboxes(RuntimeException failure) {
        List<ScenarioWorkerSandbox> closing =
                new ArrayList<>(sandboxes);
        sandboxes.clear();
        Collections.reverse(closing);
        for (ScenarioWorkerSandbox sandbox : closing) {
            try {
                sandbox.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
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

    private static WebSocketWorkerTransport createWorker(
            String workerId,
            URI endpointUri,
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
    ) {
        return new WebSocketWorkerTransport(
                new OkHttpTextWebSocketClient(
                        endpointUri,
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
        List<GroupAssembly> resolved = new ArrayList<>(configs.size());
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
            resolved.add(new GroupAssembly(
                    config,
                    List.copyOf(definitions)
            ));
        }
        return List.copyOf(resolved);
    }

    @FunctionalInterface
    interface WorkerFactory {
        WebSocketWorkerTransport create(
                String workerId,
                URI endpointUri,
                ScenarioWorkerGroupConfig config,
                List<WorkerEventDefinition<?>> definitions
        );
    }

    interface WorkerControl extends AutoCloseable {
        String register(
                String workerGroupId,
                String clientWorkerKey,
                Duration timeout
        );

        URI bind(
                String workerGroupId,
                String clientWorkerKey,
                String workerId,
                Map<String, Object> workerProperties,
                Duration timeout
        );

        @Override
        void close();
    }

    private static final class HttpWorkerControl
            implements WorkerControl {
        private final OkHttpWorkerControlClient client;

        private HttpWorkerControl(OkHttpWorkerControlClient client) {
            this.client = client;
        }

        @Override
        public String register(
                String workerGroupId,
                String clientWorkerKey,
                Duration timeout
        ) {
            try {
                return client.register(
                        workerGroupId,
                        clientWorkerKey,
                        timeout
                );
            } catch (IOException error) {
                throw new ScenarioWorkerAssemblyException(
                        WORKER_START_FAILED,
                        "workerControl.register",
                        "Worker identity registration failed",
                        error
                );
            }
        }

        @Override
        public URI bind(
                String workerGroupId,
                String clientWorkerKey,
                String workerId,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            try {
                return client.bind(
                        workerGroupId,
                        clientWorkerKey,
                        workerId,
                        WorkerTransportType.WEBSOCKET,
                        workerProperties,
                        timeout
                );
            } catch (IOException error) {
                throw new ScenarioWorkerAssemblyException(
                        WORKER_START_FAILED,
                        "workerControl.bind",
                        "Worker binding failed",
                        error
                );
            }
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private record GroupAssembly(
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
    ) {
    }

    private record WorkerHandle(
            ScenarioWorkerGroupConfig groupConfig,
            ScenarioWorkerConfig workerConfig,
            String workerId,
            WebSocketWorkerTransport transport
    ) {
    }

    private record PreparedWorker(
            GroupAssembly group,
            ScenarioWorkerConfig workerConfig,
            ScenarioWorkerSandbox sandbox,
            Map<String, Object> workerProperties,
            String workerId
    ) {
    }
}
