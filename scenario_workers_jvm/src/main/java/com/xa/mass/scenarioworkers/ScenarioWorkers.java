package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScenarioWorkers implements AutoCloseable {

    private static final int WORKER_START_FAILED = 14004;
    private static final String EXTENSION_WORKER_EVENT_PREFIX =
            "extension.worker.";

    private final URI runtimeApiBaseUrl;
    private final List<GroupAssembly> groups;
    private final ScenarioWorkerLab lab;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();

    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            URI runtimeApiBaseUrl,
            String sandboxRoot,
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode,
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
        lab = new ScenarioWorkerLab(sandboxRoot);
        this.groupManagerFactory = Objects.requireNonNull(
                groupManagerFactory,
                "groupManagerFactory"
        );
    }

    public static ScenarioWorkers fromJson(
            String capabilityAssemblyJson,
            String sandboxRoot,
            URI runtimeApiBaseUrl
    ) {
        try {
            List<ScenarioWorkerGroupConfig> configs =
                    ScenarioWorkersJsonParser.parse(capabilityAssemblyJson);
            return new ScenarioWorkers(
                    runtimeApiBaseUrl,
                    sandboxRoot,
                    configs,
                    availableDefinitionExtensions(),
                    ScenarioWorkers::createManager
            );
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.parseConfig",
                    "Scenario capability assembly is invalid: "
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
            }
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            RuntimeException closeFailure = closeManagers(null);
            if (closeFailure != null) {
                failure.addSuppressed(closeFailure);
            }
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
        if (failure != null) {
            throw failure;
        }
    }

    private List<PreparedGroup> prepareGroups() {
        if (groups.isEmpty()) {
            return List.of();
        }
        List<ScenarioWorkerGroupConfig> configs = groups.stream()
                .map(GroupAssembly::config)
                .toList();
        List<ScenarioWorkerLab.DiscoveredGroup> discovered =
                lab.prepare(configs);
        if (discovered.size() != groups.size()) {
            throw new IllegalStateException(
                    "Scenario Worker Lab returned incomplete groups"
            );
        }

        List<PreparedGroup> preparedGroups = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            GroupAssembly group = groups.get(index);
            ScenarioWorkerLab.DiscoveredGroup discoveredGroup =
                    discovered.get(index);
            if (!group.config().equals(discoveredGroup.config())) {
                throw new IllegalStateException(
                        "Scenario Worker Lab changed WorkerGroup order"
                );
            }
            if (discoveredGroup.workers().isEmpty()) {
                continue;
            }
            List<PreparedReplica> replicas = new ArrayList<>();
            for (ScenarioWorkerStateFile worker
                    : discoveredGroup.workers()) {
                replicas.add(new PreparedReplica(
                        worker.clientWorkerKey(),
                        worker.workerProperties(),
                        worker
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
                    preparedGroup,
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
                    definition.eventName(),
                    definition
            );
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate Scenario Worker eventCode: "
                                + definition.eventName()
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
                    || !eventCode.equals(definition.eventName())) {
                throw new IllegalArgumentException(
                        "Definition key does not match eventCode: "
                                + eventCode
                );
            }
            if (!eventCode.startsWith(EXTENSION_WORKER_EVENT_PREFIX)) {
                throw new IllegalArgumentException(
                        "Scenario Definition must be a Worker extension: "
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
            PreparedGroup preparedGroup,
            JavaWorkerManager manager
    ) {
    }
}
