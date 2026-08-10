package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScenarioWorkers implements AutoCloseable {

    private static final int WORKER_START_FAILED = 14004;

    private final URI runtimeApiBaseUrl;
    private final List<GroupAssembly> groups;
    private final ScenarioWorkerIndexUpdater indexUpdater;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();
    private final List<ScenarioWorkerSandbox> sandboxes =
            new ArrayList<>();

    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            URI runtimeApiBaseUrl,
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode,
            ScenarioWorkerIndexClient indexClient,
            GroupManagerFactory groupManagerFactory
    ) {
        this.runtimeApiBaseUrl = Objects.requireNonNull(
                runtimeApiBaseUrl,
                "runtimeApiBaseUrl"
        );
        groups = resolveGroups(
                configs,
                immutableDefinitionExtensions(
                        availableExtensionsByEventCode
                )
        );
        indexUpdater = new ScenarioWorkerIndexUpdater(indexClient);
        this.groupManagerFactory = Objects.requireNonNull(
                groupManagerFactory,
                "groupManagerFactory"
        );
    }

    public static ScenarioWorkers fromJson(
            String workerConfigJson,
            URI runtimeApiBaseUrl
    ) {
        try {
            List<ScenarioWorkerGroupConfig> configs =
                    ScenarioWorkersJsonParser.parse(workerConfigJson);
            ScenarioWorkerIndexClient indexClient =
                    new HttpScenarioWorkerIndexClient(runtimeApiBaseUrl);
            return new ScenarioWorkers(
                    runtimeApiBaseUrl,
                    configs,
                    availableDefinitionExtensions(),
                    indexClient,
                    ScenarioWorkers::createManager
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
            List<PreparedGroup> preparedGroups = prepareGroups();
            if (!preparedGroups.isEmpty()) {
                createManagers(preparedGroups);
                RuntimeException startFailure = startManagers();
                if (startFailure != null) {
                    throw startFailure;
                }
                updateIndexes();
            }
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
        RuntimeException failure = closeManagers(null);
        failure = closeSandboxes(failure);
        if (failure != null) {
            throw failure;
        }
    }

    private List<PreparedGroup> prepareGroups() {
        List<PreparedGroup> preparedGroups = new ArrayList<>();
        for (GroupAssembly group : groups) {
            List<PreparedReplica> replicas = new ArrayList<>();
            for (ScenarioWorkerConfig worker : group.config().workers()) {
                Map<String, Object> workerProperties =
                        worker.workerProperties();
                WorkerIdentityStore identityStore =
                        WorkerIdentityStore.noCache();
                if (worker.sandboxDirectory() != null) {
                    ScenarioWorkerSandbox sandbox =
                            ScenarioWorkerSandbox.open(
                                    worker.sandboxDirectory(),
                                    group.config().workerGroupId(),
                                    worker.clientWorkerKey(),
                                    worker.workerProperties()
                            );
                    sandboxes.add(sandbox);
                    workerProperties = sandbox.workerProperties();
                    identityStore = sandbox;
                }
                replicas.add(new PreparedReplica(
                        worker.clientWorkerKey(),
                        workerProperties,
                        identityStore
                ));
            }
            preparedGroups.add(new PreparedGroup(
                    group,
                    List.copyOf(replicas)
            ));
        }
        return List.copyOf(preparedGroups);
    }

    private void createManagers(List<PreparedGroup> preparedGroups) {
        for (PreparedGroup preparedGroup : preparedGroups) {
            JavaWorkerManager manager = Objects.requireNonNull(
                    groupManagerFactory.create(
                            runtimeApiBaseUrl,
                            preparedGroup
                    ),
                    "groupManager"
            );
            managedGroups.add(new ManagedGroup(
                    preparedGroup.group().config(),
                    manager
            ));
        }
    }

    private RuntimeException startManagers() {
        RuntimeException failure = null;
        for (ManagedGroup managedGroup : managedGroups) {
            try {
                managedGroup.manager().start();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private void updateIndexes() {
        for (ManagedGroup managedGroup : managedGroups) {
            indexUpdater.update(
                    managedGroup.config(),
                    managedGroup.manager()
            );
        }
    }

    private RuntimeException closeManagers(RuntimeException failure) {
        List<ManagedGroup> closing = new ArrayList<>(managedGroups);
        managedGroups.clear();
        Collections.reverse(closing);
        for (ManagedGroup managedGroup : closing) {
            try {
                managedGroup.manager().close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
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

    private void closeResourcesAndSuppress(RuntimeException failure) {
        RuntimeException closeFailure = closeManagers(null);
        closeFailure = closeSandboxes(closeFailure);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static JavaWorkerManager createManager(
            URI runtimeApiBaseUrl,
            PreparedGroup preparedGroup
    ) {
        GroupAssembly group = preparedGroup.group();
        ScenarioWorkerGroupConfig config = group.config();
        JavaWorkerManager.Builder builder = JavaWorkerManager.builder(
                        runtimeApiBaseUrl,
                        config.workerGroupId(),
                        WorkerTransportType.WEBSOCKET
                )
                .extendEventDefinitions(group.definitionExtensions())
                .options(WorkerConnectionOptions.of(
                        config.requestTimeout(),
                        config.reconnectPolicy()
                ));
        for (PreparedReplica replica : preparedGroup.replicas()) {
            builder.replica(
                    replica.clientWorkerKey(),
                    replica.identityStore(),
                    replica::workerProperties
            );
        }
        return builder.build();
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

    private static Map<String, WorkerEventDefinition<?>>
    availableDefinitionExtensions() {
        Map<String, WorkerEventDefinition<?>> definitions =
                new LinkedHashMap<>();
        addDefinitionExtensions(
                definitions,
                PhoneNumberWorkerEvents.definitions()
        );
        addDefinitionExtensions(
                definitions,
                StringUtilityWorkerEvents.definitions()
        );
        return Collections.unmodifiableMap(definitions);
    }

    private static void addDefinitionExtensions(
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
    immutableDefinitionExtensions(
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode
    ) {
        Objects.requireNonNull(
                availableExtensionsByEventCode,
                "availableExtensionsByEventCode"
        );
        Map<String, WorkerEventDefinition<?>> copy = new LinkedHashMap<>();
        availableExtensionsByEventCode.forEach((eventCode, definition) -> {
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
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode
    ) {
        Objects.requireNonNull(configs, "configs");
        List<GroupAssembly> resolved = new ArrayList<>(configs.size());
        for (ScenarioWorkerGroupConfig config : configs) {
            List<WorkerEventDefinition<?>> definitionExtensions =
                    new ArrayList<>();
            for (String eventCode : config.eventCodes()) {
                WorkerEventDefinition<?> definition =
                        availableExtensionsByEventCode.get(eventCode);
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "WorkerGroup "
                                    + config.workerGroupId()
                                    + " references unknown eventCode "
                                    + eventCode
                    );
                }
                definitionExtensions.add(definition);
            }
            resolved.add(new GroupAssembly(
                    config,
                    List.copyOf(definitionExtensions)
            ));
        }
        return List.copyOf(resolved);
    }

    @FunctionalInterface
    interface GroupManagerFactory {

        JavaWorkerManager create(
                URI runtimeApiBaseUrl,
                PreparedGroup preparedGroup
        );
    }

    record GroupAssembly(
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitionExtensions
    ) {
    }

    record PreparedReplica(
            String clientWorkerKey,
            Map<String, Object> workerProperties,
            WorkerIdentityStore identityStore
    ) {
    }

    record PreparedGroup(
            GroupAssembly group,
            List<PreparedReplica> replicas
    ) {
    }

    private record ManagedGroup(
            ScenarioWorkerGroupConfig config,
            JavaWorkerManager manager
    ) {
    }
}
