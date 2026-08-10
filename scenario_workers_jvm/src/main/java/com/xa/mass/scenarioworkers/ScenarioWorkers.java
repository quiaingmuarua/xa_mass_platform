package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerHostResources;
import com.xa.mass.worker.javase.JavaWorkerManager;
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
    private final HostResourcesFactory hostResourcesFactory;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();
    private final List<ScenarioWorkerSandbox> sandboxes =
            new ArrayList<>();

    private JavaWorkerHostResources hostResources;
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            URI runtimeApiBaseUrl,
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>> definitionsByEventCode,
            ScenarioWorkerIndexClient indexClient,
            HostResourcesFactory hostResourcesFactory,
            GroupManagerFactory groupManagerFactory
    ) {
        this.runtimeApiBaseUrl = Objects.requireNonNull(
                runtimeApiBaseUrl,
                "runtimeApiBaseUrl"
        );
        groups = resolveGroups(
                configs,
                immutableDefinitions(definitionsByEventCode)
        );
        indexUpdater = new ScenarioWorkerIndexUpdater(indexClient);
        this.hostResourcesFactory = Objects.requireNonNull(
                hostResourcesFactory,
                "hostResourcesFactory"
        );
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
                    builtInDefinitions(),
                    indexClient,
                    replicaCount -> JavaWorkerHostResources.create(
                            replicaCount,
                            "xa-scenario-worker",
                            true
                    ),
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
                hostResources = Objects.requireNonNull(
                        hostResourcesFactory.create(workerCount()),
                        "hostResources"
                );
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
        failure = closeHostResources(failure);
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
                            preparedGroup,
                            hostResources
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

    private RuntimeException closeHostResources(RuntimeException failure) {
        JavaWorkerHostResources closing = hostResources;
        hostResources = null;
        if (closing == null) {
            return failure;
        }
        try {
            closing.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        return failure;
    }

    private void closeResourcesAndSuppress(RuntimeException failure) {
        RuntimeException closeFailure = closeManagers(null);
        closeFailure = closeSandboxes(closeFailure);
        closeFailure = closeHostResources(closeFailure);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static JavaWorkerManager createManager(
            URI runtimeApiBaseUrl,
            PreparedGroup preparedGroup,
            JavaWorkerHostResources hostResources
    ) {
        GroupAssembly group = preparedGroup.group();
        ScenarioWorkerGroupConfig config = group.config();
        JavaWorkerManager.Builder builder = JavaWorkerManager.builder(
                        runtimeApiBaseUrl,
                        config.workerGroupId(),
                        WorkerTransportType.WEBSOCKET
                )
                .executionResources(hostResources.executionResources())
                .eventDefinitions(group.definitions())
                .requestTimeout(config.requestTimeout())
                .reconnectPolicy(config.reconnectPolicy());
        for (PreparedReplica replica : preparedGroup.replicas()) {
            builder.replica(
                    replica.clientWorkerKey(),
                    replica.identityStore(),
                    replica::workerProperties
            );
        }
        return builder.build();
    }

    private int workerCount() {
        int count = 0;
        for (GroupAssembly group : groups) {
            count += group.config().workers().size();
        }
        return count;
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
    interface HostResourcesFactory {

        JavaWorkerHostResources create(int totalReplicaCount);
    }

    @FunctionalInterface
    interface GroupManagerFactory {

        JavaWorkerManager create(
                URI runtimeApiBaseUrl,
                PreparedGroup preparedGroup,
                JavaWorkerHostResources hostResources
        );
    }

    record GroupAssembly(
            ScenarioWorkerGroupConfig config,
            List<WorkerEventDefinition<?>> definitions
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
