package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
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
    private final ScenarioWorkerCommandCheckpoints commandCheckpoints;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();
    private final Map<String, ManagedGroup> managedGroupsById =
            new LinkedHashMap<>();

    private boolean started;
    private boolean closed;
    private int initialWorkerCount;

    ScenarioWorkers(
            URI runtimeApiBaseUrl,
            String sandboxRoot,
            List<ScenarioWorkerGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode,
            GroupManagerFactory groupManagerFactory,
            ScenarioWorkerCommandCheckpoints commandCheckpoints
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
        this.commandCheckpoints = Objects.requireNonNull(
                commandCheckpoints,
                "commandCheckpoints"
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
            ScenarioWorkerCommandCheckpoints checkpoints =
                    new ScenarioWorkerCommandCheckpoints();
            return new ScenarioWorkers(
                    runtimeApiBaseUrl,
                    sandboxRoot,
                    configs,
                    availableDefinitionExtensions(checkpoints),
                    ScenarioWorkers::createManager,
                    checkpoints
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
        start(ScenarioWorkerStartupPlan.defaults());
    }

    synchronized void start(ScenarioWorkerStartupPlan startupPlan) {
        Objects.requireNonNull(startupPlan, "startupPlan");
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
                List<ScenarioWorkerCoordinate> initialWorkers =
                        resolveInitialWorkers(startupPlan);
                RuntimeException startFailure = startWorkers(
                        startupPlan.startAll(),
                        initialWorkers
                );
                if (startFailure != null) {
                    throw startFailure;
                }
                initialWorkerCount = initialWorkers.size();
            }
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            commandCheckpoints.close();
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
        commandCheckpoints.close();
        RuntimeException failure = closeManagers(null);
        if (failure != null) {
            throw failure;
        }
    }

    synchronized List<WorkerControlSnapshot> workerSnapshots() {
        ensureControllable();
        List<WorkerControlSnapshot> snapshots = new ArrayList<>();
        for (ManagedGroup managedGroup : managedGroups) {
            for (PreparedReplica replica
                    : managedGroup.preparedGroup().replicas()) {
                snapshots.add(snapshot(managedGroup, replica, false));
            }
        }
        return List.copyOf(snapshots);
    }

    synchronized WorkerControlSnapshot workerSnapshot(
            String workerGroupId,
            String clientWorkerKey,
            boolean includeProperties
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        return snapshot(
                group,
                requireReplica(group, clientWorkerKey),
                includeProperties
        );
    }

    synchronized void startWorker(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ensureControllable();
        requireManagedGroup(workerGroupId)
                .manager()
                .start(requireClientWorkerKey(workerGroupId, clientWorkerKey));
    }

    synchronized void stopWorker(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ensureControllable();
        requireManagedGroup(workerGroupId)
                .manager()
                .stop(requireClientWorkerKey(workerGroupId, clientWorkerKey));
    }

    synchronized void replaceWorkerState(
            String workerGroupId,
            String clientWorkerKey,
            String encodedDocument
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireReplica(group, clientWorkerKey)
                .stateFile()
                .replace(encodedDocument);
    }

    synchronized int initialWorkerCount() {
        ensureControllable();
        return initialWorkerCount;
    }

    synchronized void armCommandCheckpoint(
            String workerGroupId,
            String clientWorkerKey,
            String checkpointToken,
            long maximumHoldMillis
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, clientWorkerKey);
        commandCheckpoints.arm(
                coordinate(group, replica),
                checkpointToken,
                maximumHoldMillis
        );
    }

    synchronized ScenarioWorkerCommandCheckpoints.Snapshot
    commandCheckpoint(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, clientWorkerKey);
        return commandCheckpoints.snapshot(coordinate(group, replica));
    }

    synchronized void releaseCommandCheckpoint(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, clientWorkerKey);
        commandCheckpoints.release(coordinate(group, replica));
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
            managedGroupsById.put(
                    preparedGroup.group().config().workerGroupId(),
                    managedGroups.get(managedGroups.size() - 1)
            );
        }
    }

    private List<ScenarioWorkerCoordinate> resolveInitialWorkers(
            ScenarioWorkerStartupPlan startupPlan
    ) {
        if (startupPlan.startAll()) {
            List<ScenarioWorkerCoordinate> coordinates = new ArrayList<>();
            for (ManagedGroup group : managedGroups) {
                for (PreparedReplica replica
                        : group.preparedGroup().replicas()) {
                    coordinates.add(coordinate(group, replica));
                }
            }
            return List.copyOf(coordinates);
        }
        for (ScenarioWorkerCoordinate worker
                : startupPlan.initialWorkers()) {
            ManagedGroup group = requireManagedGroup(worker.workerGroupId());
            requireReplica(group, worker.clientWorkerKey());
        }
        return startupPlan.initialWorkers();
    }

    private RuntimeException startWorkers(
            boolean startAll,
            List<ScenarioWorkerCoordinate> initialWorkers
    ) {
        RuntimeException failure = null;
        if (startAll) {
            for (ManagedGroup managedGroup : managedGroups) {
                try {
                    managedGroup.manager().start();
                } catch (RuntimeException error) {
                    failure = accumulate(failure, error);
                }
            }
            return failure;
        }
        for (ScenarioWorkerCoordinate worker : initialWorkers) {
            try {
                requireManagedGroup(worker.workerGroupId())
                        .manager()
                        .start(worker.clientWorkerKey());
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        return failure;
    }

    private RuntimeException closeManagers(RuntimeException failure) {
        List<ManagedGroup> closing = new ArrayList<>(managedGroups);
        managedGroups.clear();
        managedGroupsById.clear();
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

    private WorkerControlSnapshot snapshot(
            ManagedGroup group,
            PreparedReplica replica,
            boolean includeProperties
    ) {
        JavaWorkerManager manager = group.manager();
        WorkerLifecycle.Snapshot runtime = manager.snapshot(
                replica.clientWorkerKey()
        );
        return new WorkerControlSnapshot(
                group.preparedGroup().group().config().workerGroupId(),
                replica.clientWorkerKey(),
                manager.desiredRunning(replica.clientWorkerKey()),
                runtime,
                includeProperties
                        ? replica.stateFile().workerProperties()
                        : null
        );
    }

    private ManagedGroup requireManagedGroup(String workerGroupId) {
        ScenarioWorkerGroupConfig.requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        ManagedGroup group = managedGroupsById.get(workerGroupId);
        if (group == null) {
            throw new UnknownWorkerException(
                    "Unknown Scenario WorkerGroup: " + workerGroupId
            );
        }
        return group;
    }

    private PreparedReplica requireReplica(
            ManagedGroup group,
            String clientWorkerKey
    ) {
        String key = requireClientWorkerKey(
                group.preparedGroup().group().config().workerGroupId(),
                clientWorkerKey
        );
        for (PreparedReplica replica : group.preparedGroup().replicas()) {
            if (replica.clientWorkerKey().equals(key)) {
                return replica;
            }
        }
        throw new UnknownWorkerException(
                "Unknown Scenario Worker: " + key
        );
    }

    private String requireClientWorkerKey(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ManagedGroup group = requireManagedGroup(workerGroupId);
        ScenarioWorkerGroupConfig.requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
        boolean present = group.preparedGroup().replicas().stream()
                .anyMatch(replica -> replica.clientWorkerKey()
                        .equals(clientWorkerKey));
        if (!present) {
            throw new UnknownWorkerException(
                    "Unknown Scenario Worker: " + clientWorkerKey
            );
        }
        return clientWorkerKey;
    }

    private void ensureControllable() {
        if (!started || closed) {
            throw new IllegalStateException(
                    "Scenario Workers are not running"
            );
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
                    replica.stateFile()::workerProperties
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
    availableDefinitionExtensions(
            ScenarioWorkerCommandCheckpoints checkpoints
    ) {
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
        addDefinitionExtensions(
                definitions,
                List.of(ScenarioWorkerLabEvents.checkpoint(checkpoints))
        );
        return Collections.unmodifiableMap(definitions);
    }

    private static ScenarioWorkerCoordinate coordinate(
            ManagedGroup group,
            PreparedReplica replica
    ) {
        return new ScenarioWorkerCoordinate(
                group.preparedGroup().group().config().workerGroupId(),
                replica.clientWorkerKey()
        );
    }

    private static void requireCheckpointCapable(ManagedGroup group) {
        boolean supported = group.preparedGroup()
                .group()
                .definitionExtensions()
                .stream()
                .anyMatch(definition -> ScenarioWorkerLabEvents
                        .CHECKPOINT_EVENT_CODE
                        .equals(definition.eventName()));
        if (!supported) {
            throw new IllegalArgumentException(
                    "WorkerGroup does not install the Lab checkpoint event"
            );
        }
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
            ScenarioWorkerStateFile stateFile
    ) {
    }

    record PreparedGroup(
            GroupAssembly group,
            List<PreparedReplica> replicas
    ) {
    }

    record WorkerControlSnapshot(
            String workerGroupId,
            String clientWorkerKey,
            boolean desiredRunning,
            WorkerLifecycle.Snapshot runtime,
            Map<String, Object> workerProperties
    ) {
    }

    static final class UnknownWorkerException
            extends IllegalArgumentException {

        private UnknownWorkerException(String message) {
            super(message);
        }
    }

    private record ManagedGroup(
            PreparedGroup preparedGroup,
            JavaWorkerManager manager
    ) {
    }
}
