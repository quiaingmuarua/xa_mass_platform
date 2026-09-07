package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                RuntimeException startFailure = startWorkers(initialWorkers);
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
            String labWorkerKey,
            boolean includeProperties
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        return snapshot(
                group,
                requireReplica(group, labWorkerKey),
                includeProperties
        );
    }

    void startWorker(
            String workerGroupId,
            String labWorkerKey
    ) {
        ManagedGroup group;
        String replicaKey;
        synchronized (this) {
            ensureControllable();
            group = requireManagedGroup(workerGroupId);
            replicaKey = requireLabWorkerKey(
                    workerGroupId,
                    labWorkerKey
            );
        }
        group.manager().prepareAndStart(List.of(replicaKey));
    }

    void stopWorker(
            String workerGroupId,
            String labWorkerKey
    ) {
        ManagedGroup group;
        String replicaKey;
        synchronized (this) {
            ensureControllable();
            group = requireManagedGroup(workerGroupId);
            replicaKey = requireLabWorkerKey(
                    workerGroupId,
                    labWorkerKey
            );
        }
        group.manager().stop(replicaKey);
    }

    void stopWorkers(List<ScenarioWorkerCoordinate> coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        List<WorkerStopTarget> targets = new ArrayList<>(coordinates.size());
        synchronized (this) {
            ensureControllable();
            for (ScenarioWorkerCoordinate coordinate : coordinates) {
                Objects.requireNonNull(coordinate, "coordinate");
                ManagedGroup group = requireManagedGroup(
                        coordinate.workerGroupId()
                );
                PreparedReplica replica = requireReplica(
                        group,
                        coordinate.labWorkerKey()
                );
                targets.add(new WorkerStopTarget(
                        group.manager(),
                        replica.labWorkerKey()
                ));
            }
        }
        for (WorkerStopTarget target : targets) {
            target.manager().stop(target.replicaKey());
        }
    }

    synchronized void replaceWorkerState(
            String workerGroupId,
            String labWorkerKey,
            String encodedDocument
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireReplica(group, labWorkerKey)
                .stateFile()
                .replace(encodedDocument);
    }

    synchronized int initialWorkerCount() {
        ensureControllable();
        return initialWorkerCount;
    }

    synchronized void armCommandCheckpoint(
            String workerGroupId,
            String labWorkerKey,
            String checkpointToken,
            long maximumHoldMillis
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, labWorkerKey);
        commandCheckpoints.arm(
                coordinate(group, replica),
                checkpointToken,
                maximumHoldMillis
        );
    }

    synchronized ScenarioWorkerCommandCheckpoints.Snapshot
    commandCheckpoint(
            String workerGroupId,
            String labWorkerKey
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, labWorkerKey);
        return commandCheckpoints.snapshot(coordinate(group, replica));
    }

    synchronized void releaseCommandCheckpoint(
            String workerGroupId,
            String labWorkerKey
    ) {
        ensureControllable();
        ManagedGroup group = requireManagedGroup(workerGroupId);
        requireCheckpointCapable(group);
        PreparedReplica replica = requireReplica(group, labWorkerKey);
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
                        worker.labWorkerKey(),
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
            requireReplica(group, worker.labWorkerKey());
        }
        return startupPlan.initialWorkers();
    }

    private RuntimeException startWorkers(
            List<ScenarioWorkerCoordinate> initialWorkers
    ) {
        RuntimeException failure = null;
        Set<ScenarioWorkerCoordinate> selected = new HashSet<>(
                initialWorkers
        );
        for (ManagedGroup managedGroup : managedGroups) {
            Map<String, List<String>> keysByInventory =
                    new LinkedHashMap<>();
            for (PreparedReplica replica
                    : managedGroup.preparedGroup().replicas()) {
                if (!selected.contains(coordinate(
                        managedGroup,
                        replica
                ))) {
                    continue;
                }
                keysByInventory.computeIfAbsent(
                        replica.stateFile().inventoryFileName(),
                        ignored -> new ArrayList<>()
                ).add(replica.labWorkerKey());
            }
            for (List<String> keys : keysByInventory.values()) {
                try {
                    managedGroup.manager().prepareAndStart(keys);
                } catch (RuntimeException error) {
                    failure = accumulate(failure, error);
                }
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
                replica.labWorkerKey()
        );
        return new WorkerControlSnapshot(
                group.preparedGroup().group().config().workerGroupId(),
                replica.labWorkerKey(),
                manager.desiredRunning(replica.labWorkerKey()),
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
            String labWorkerKey
    ) {
        String key = requireLabWorkerKey(
                group.preparedGroup().group().config().workerGroupId(),
                labWorkerKey
        );
        for (PreparedReplica replica : group.preparedGroup().replicas()) {
            if (replica.labWorkerKey().equals(key)) {
                return replica;
            }
        }
        throw new UnknownWorkerException(
                "Unknown Scenario Worker: " + key
        );
    }

    private String requireLabWorkerKey(
            String workerGroupId,
            String labWorkerKey
    ) {
        ManagedGroup group = requireManagedGroup(workerGroupId);
        ScenarioWorkerGroupConfig.requireNonBlank(
                labWorkerKey,
                "labWorkerKey"
        );
        boolean present = group.preparedGroup().replicas().stream()
                .anyMatch(replica -> replica.labWorkerKey()
                        .equals(labWorkerKey));
        if (!present) {
            throw new UnknownWorkerException(
                    "Unknown Scenario Worker: " + labWorkerKey
            );
        }
        return labWorkerKey;
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
                .batchWorkerKind("SCENARIO_LAB")
                .options(WorkerConnectionOptions.of(
                        config.requestTimeout(),
                        config.reconnectPolicy()
                ));
        for (PreparedReplica replica : preparedGroup.replicas()) {
            builder.replica(
                    replica.labWorkerKey(),
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
                ScenarioWorkerLabEvents.backgroundFaults()
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
                replica.labWorkerKey()
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
            String labWorkerKey,
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
            String labWorkerKey,
            boolean desiredRunning,
            WorkerLifecycle.Snapshot runtime,
            Map<String, String> workerProperties
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

    private record WorkerStopTarget(
            JavaWorkerManager manager,
            String replicaKey
    ) {
    }
}
