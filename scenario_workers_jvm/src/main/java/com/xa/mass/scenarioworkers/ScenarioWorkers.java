package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.javase.JavaWorker;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.runtime.WorkerExecutionResources;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScenarioWorkers implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(
            ScenarioWorkers.class.getName()
    );
    private static final int WORKER_START_FAILED = 14004;
    private static final int WORKER_CONNECT_TIMEOUT = 14005;
    private static final int WORKER_INDEX_FAILED = 14010;
    private static final Duration INDEX_RETRY_INTERVAL =
            Duration.ofMillis(250);

    private final List<GroupAssembly> groups;
    private final ScenarioWorkerIndexClient indexClient;
    private final WorkerFactory workerFactory;
    private final AutoCloseable executionResourcesOwner;
    private final List<WorkerHandle> workers = new ArrayList<>();
    private final List<ScenarioWorkerSandbox> sandboxes =
            new ArrayList<>();
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            ScenarioWorkerIndexClient indexClient,
            WorkerFactory workerFactory
    ) {
        this(
                configs,
                definitionsByEventCode,
                indexClient,
                workerFactory,
                () -> {
                }
        );
    }

    ScenarioWorkers(
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            ScenarioWorkerIndexClient indexClient,
            WorkerFactory workerFactory,
            AutoCloseable executionResourcesOwner
    ) {
        groups = resolveGroups(
                configs,
                immutableDefinitions(definitionsByEventCode)
        );
        this.indexClient = Objects.requireNonNull(
                indexClient,
                "indexClient"
        );
        this.workerFactory = Objects.requireNonNull(
                workerFactory,
                "workerFactory"
        );
        this.executionResourcesOwner = Objects.requireNonNull(
                executionResourcesOwner,
                "executionResourcesOwner"
        );
    }

    public static ScenarioWorkers fromJson(
            String workerConfigJson,
            URI runtimeApiBaseUrl
    ) {
        ScenarioWorkerExecutionResources executionResources = null;
        try {
            List<ScenarioWorkerGroupConfig> configs =
                    ScenarioWorkersJsonParser.parse(workerConfigJson);
            executionResources = ScenarioWorkerExecutionResources.create(
                    workerCount(configs)
            );
            ScenarioWorkerExecutionResources shared = executionResources;
            return new ScenarioWorkers(
                    configs,
                    builtInDefinitions(),
                    new HttpScenarioWorkerIndexClient(runtimeApiBaseUrl),
                    (group, worker, identityStore, properties,
                     definitions) -> createWorker(
                            runtimeApiBaseUrl,
                            group,
                            worker,
                            identityStore,
                            properties,
                            definitions,
                            shared.resources()
                    ),
                    shared
            );
        } catch (IllegalArgumentException error) {
            closeOnAssemblyFailure(executionResources, error);
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.parseConfig",
                    "Scenario Worker configuration is invalid: "
                            + error.getMessage(),
                    error
            );
        } catch (RuntimeException error) {
            closeOnAssemblyFailure(executionResources, error);
            throw error;
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
            createAndStartWorkers(preparedWorkers);
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
        failure = closeSandboxes(failure);
        failure = closeExecutionResources(failure);
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
                WorkerIdentityStore identityStore =
                        WorkerIdentityStore.noCache();
                if (worker.sandboxDirectory() != null) {
                    sandbox = ScenarioWorkerSandbox.open(
                            worker.sandboxDirectory(),
                            group.config().workerGroupId(),
                            worker.clientWorkerKey(),
                            worker.workerProperties()
                    );
                    sandboxes.add(sandbox);
                    workerProperties = sandbox.workerProperties();
                    identityStore = sandbox;
                }
                prepared.add(new PreparedWorker(
                        group,
                        worker,
                        sandbox,
                        workerProperties,
                        identityStore
                ));
            }
        }
        return List.copyOf(prepared);
    }

    private void createAndStartWorkers(
            List<PreparedWorker> preparedWorkers
    ) {
        for (PreparedWorker prepared : preparedWorkers) {
            GroupAssembly group = prepared.group();
            ScenarioWorkerConfig worker = prepared.workerConfig();
            WorkerRuntimeHandle runtime = workerFactory.create(
                    group.config(),
                    worker,
                    prepared.identityStore(),
                    () -> prepared.workerProperties(),
                    group.definitions()
            );
            workers.add(new WorkerHandle(
                    group.config(),
                    worker,
                    runtime
            ));
            runtime.start();
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
                        worker -> worker.runtime().isConnected()
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
                    .filter(worker -> worker.runtime().isConnected())
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
                        INDEX_RETRY_INTERVAL,
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
                worker.runtime().close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private void closeResourcesAndSuppress(RuntimeException failure) {
        RuntimeException closeFailure = closeWorkers();
        closeFailure = closeSandboxes(closeFailure);
        closeFailure = closeExecutionResources(closeFailure);
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

    private RuntimeException closeExecutionResources(
            RuntimeException failure
    ) {
        try {
            executionResourcesOwner.close();
        } catch (Exception error) {
            RuntimeException runtimeError = error instanceof RuntimeException
                    ? (RuntimeException) error
                    : new IllegalStateException(
                            "Could not close Scenario Worker execution resources",
                            error
                    );
            failure = accumulate(failure, runtimeError);
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

    private static WorkerRuntimeHandle createWorker(
            URI runtimeApiBaseUrl,
            ScenarioWorkerGroupConfig config,
            ScenarioWorkerConfig workerConfig,
            WorkerIdentityStore identityStore,
            WorkerPropertiesProvider propertiesProvider,
            List<WorkerEventDefinition<?>> definitions,
            WorkerExecutionResources executionResources
    ) {
        JavaWorker worker = JavaWorker.builder(
                        runtimeApiBaseUrl,
                        config.workerGroupId(),
                        workerConfig.clientWorkerKey(),
                        WorkerTransportType.WEBSOCKET
                )
                .identityStore(identityStore)
                .workerProperties(propertiesProvider)
                .eventDefinitions(definitions)
                .executionResources(executionResources)
                .requestTimeout(config.requestTimeout())
                .retryPolicy(config.retryPolicy())
                .build();
        return new WorkerRuntimeHandle() {
            @Override
            public void start() {
                worker.start();
            }

            @Override
            public boolean isConnected() {
                return worker.isConnected();
            }

            @Override
            public String workerId() {
                return worker.snapshot().workerId();
            }

            @Override
            public void close() {
                worker.close();
            }
        };
    }

    private static int workerCount(
            List<ScenarioWorkerGroupConfig> configs
    ) {
        int count = 0;
        for (ScenarioWorkerGroupConfig config : configs) {
            count += config.workers().size();
        }
        return count;
    }

    private static void closeOnAssemblyFailure(
            AutoCloseable resource,
            RuntimeException failure
    ) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static final class ScenarioWorkerExecutionResources
            implements AutoCloseable {

        private final ExecutorService controlExecutor;
        private final ExecutorService handlerExecutor;
        private final ScheduledExecutorService retryScheduler;
        private final WorkerExecutionResources resources;
        private boolean closed;

        private ScenarioWorkerExecutionResources(
                ExecutorService controlExecutor,
                ExecutorService handlerExecutor,
                ScheduledExecutorService retryScheduler
        ) {
            this.controlExecutor = controlExecutor;
            this.handlerExecutor = handlerExecutor;
            this.retryScheduler = retryScheduler;
            resources = WorkerExecutionResources.of(
                    controlExecutor,
                    handlerExecutor,
                    retryScheduler
            );
        }

        private static ScenarioWorkerExecutionResources create(
                int workerCount
        ) {
            int workers = Math.max(1, workerCount);
            int controlThreads = Math.max(1, Math.min(workers, 4));
            int handlerThreads = Math.max(
                    1,
                    Math.min(
                            workers,
                            Math.max(
                                    2,
                                    Runtime.getRuntime().availableProcessors()
                            )
                    )
            );
            return new ScenarioWorkerExecutionResources(
                    Executors.newFixedThreadPool(
                            controlThreads,
                            daemonThreadFactory("xa-scenario-worker-control")
                    ),
                    Executors.newFixedThreadPool(
                            handlerThreads,
                            daemonThreadFactory("xa-scenario-worker-handler")
                    ),
                    Executors.newSingleThreadScheduledExecutor(
                            daemonThreadFactory("xa-scenario-worker-retry")
                    )
            );
        }

        private WorkerExecutionResources resources() {
            return resources;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            retryScheduler.shutdownNow();
            handlerExecutor.shutdownNow();
            controlExecutor.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    prefix + "-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
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
        WorkerRuntimeHandle create(
                ScenarioWorkerGroupConfig config,
                ScenarioWorkerConfig workerConfig,
                WorkerIdentityStore identityStore,
                WorkerPropertiesProvider propertiesProvider,
                List<WorkerEventDefinition<?>> definitions
        );
    }

    interface WorkerRuntimeHandle extends AutoCloseable {

        void start();

        boolean isConnected();

        String workerId();

        @Override
        void close();
    }

    private record GroupAssembly(
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
    ) {
    }

    private record WorkerHandle(
            ScenarioWorkerGroupConfig groupConfig,
            ScenarioWorkerConfig workerConfig,
            WorkerRuntimeHandle runtime
    ) {

        String workerId() {
            String value = runtime.workerId();
            if (value == null) {
                throw new ScenarioWorkerAssemblyException(
                        WORKER_START_FAILED,
                        "scenarioWorkers.workerId",
                        "Connected Scenario Worker has no workerId"
                );
            }
            return value;
        }
    }

    private record PreparedWorker(
            GroupAssembly group,
            ScenarioWorkerConfig workerConfig,
            ScenarioWorkerSandbox sandbox,
            Map<String, Object> workerProperties,
            WorkerIdentityStore identityStore
    ) {
    }
}
