package com.xa.mass.workersimulator;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workersimulator.sms.SmsScenario;
import com.xa.mass.workersimulator.sms.ListeningRegistry;
import com.xa.mass.workersimulator.messaging.MessageScenario;
import java.util.concurrent.atomic.AtomicReference;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkerSimulator implements AutoCloseable {

    private static final int WORKER_START_FAILED = 14004;
    private static final String EXTENSION_WORKER_EVENT_PREFIX =
            "extension.worker.";

    private final URI runtimeApiBaseUrl;
    private final List<GroupAssembly> groups;
    private final WorkerSimulatorLab lab;
    private final SmsScenario sms;
    private final MessageScenario messages;
    private final WorkerSimulatorCommandCheckpoints commandCheckpoints;
    private final WorkerSimulatorExecutionWitnesses executionWitnesses;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();
    private final Map<String, ManagedGroup> managedGroupsById =
            new LinkedHashMap<>();
    private final Set<WorkerSimulatorCoordinate> propertiesOperations =
            ConcurrentHashMap.newKeySet();

    private boolean started;
    private boolean closed;
    private int initialWorkerCount;

    WorkerSimulator(
            URI runtimeApiBaseUrl,
            String sandboxRoot,
            List<WorkerSimulatorGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode,
            GroupManagerFactory groupManagerFactory,
            WorkerSimulatorCommandCheckpoints commandCheckpoints,
            WorkerSimulatorExecutionWitnesses executionWitnesses
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
        lab = new WorkerSimulatorLab(sandboxRoot);
        sms = configs.stream().anyMatch(WorkerSimulator::usesSms) ? new SmsScenario() : null;
        messages = configs.stream().anyMatch(WorkerSimulator::usesMessages) ? new MessageScenario() : null;
        this.groupManagerFactory = groupManagerFactory == null ? this::createManager : groupManagerFactory;
        this.commandCheckpoints = Objects.requireNonNull(
                commandCheckpoints,
                "commandCheckpoints"
        );
        this.executionWitnesses = Objects.requireNonNull(executionWitnesses, "executionWitnesses");
    }

    static WorkerSimulator create(WorkerSimulatorConfig config) {
        try {
            WorkerSimulatorCommandCheckpoints checkpoints = new WorkerSimulatorCommandCheckpoints();
            return new WorkerSimulator(config.runtimeApiBaseUrl(), config.sandboxRoot().toString(),
                    config.workerGroups(), availableDefinitionExtensions(checkpoints), null,
                    checkpoints, new WorkerSimulatorExecutionWitnesses());
        } catch (IllegalArgumentException error) {
            throw new WorkerSimulatorAssemblyException(14012, "workerSimulator.create",
                    "Worker Simulator configuration is invalid: " + error.getMessage(), error);
        }
    }

    boolean isSms() { return sms != null; }
    boolean hasMessages() { return messages != null; }
    MessageScenario messageScenario() { return Objects.requireNonNull(messages, "Messages not enabled"); }

    SmsScenario smsScenario() {
        if (sms == null) throw new IllegalStateException("SMS scenario is not enabled");
        return sms;
    }

    synchronized Map<String, Object> smsHealth() {
        ensureControllable();
        long prepared = managedGroups.stream().flatMap(group -> group.manager().snapshots().values().stream())
                .filter(snapshot -> snapshot.workerId() != null).count();
        return Map.of("started", started, "prepared", prepared, "numbers", initialWorkerCount);
    }

    public synchronized void start() {
        start(WorkerSimulatorStartupPlan.defaults());
    }

    synchronized void start(WorkerSimulatorStartupPlan startupPlan) {
        Objects.requireNonNull(startupPlan, "startupPlan");
        if (closed) {
            throw new IllegalStateException("Worker Simulator is closed");
        }
        if (started) {
            return;
        }

        try {
            List<PreparedGroup> preparedGroups = prepareGroups(startupPlan);
            if (!preparedGroups.isEmpty()) {
                createManagers(preparedGroups);
                List<WorkerSimulatorCoordinate> initialWorkers =
                        resolveInitialWorkers(startupPlan);
                RuntimeException startFailure = startWorkers(initialWorkers);
                if (startFailure != null) {
                    throw startFailure;
                }
                initialWorkerCount = initialWorkers.size();
            }
            started = true;
            if (sms != null) sms.start();
        } catch (RuntimeException failure) {
            closed = true;
            commandCheckpoints.close();
            if (sms != null) sms.close();
            if (messages != null) messages.close();
            RuntimeException closeFailure = closeManagers(null);
            if (sms != null) sms.awaitClosed();
            if (closeFailure != null) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof WorkerSimulatorAssemblyException) {
                throw failure;
            }
            throw new WorkerSimulatorAssemblyException(
                    WORKER_START_FAILED,
                    "workerSimulator.start",
                    "Could not start Worker Simulator",
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
        if (sms != null) sms.close();
        if (messages != null) messages.close();
        RuntimeException failure = closeManagers(null);
        if (sms != null) sms.awaitClosed();
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
        WorkerSimulatorCoordinate target = beginPropertiesOperation(workerGroupId, labWorkerKey);
        try {
            ManagedGroup group;
            PreparedReplica replica;
            synchronized (this) {
                ensureControllable();
                group = requireManagedGroup(workerGroupId);
                replica = requireReplica(group, labWorkerKey);
                applyProperties(group, replica, replica.stateFile().readProperties(), false);
                if (replica.sim != null) sms.registry.beginStart(replica.sim);
                if (replica.sender != null) messages.start(replica.sender);
            }
            try {
                group.manager().prepareAndStart(List.of(replica.replicaKey()));
            } catch (RuntimeException | Error failure) {
                stopBusiness(replica);
                throw failure;
            } finally {
                try {
                    if ((replica.sim != null && !sms.registry.startStillRequested(replica.sim))
                            || (replica.sender != null && !messages.startStillRequested(replica.sender))) {
                        group.manager().stop(replica.replicaKey());
                    }
                } finally {
                    if (replica.sim != null) sms.registry.endStart(replica.sim);
                }
            }
        } finally {
            propertiesOperations.remove(target);
        }
    }

    void stopWorker(
            String workerGroupId,
            String labWorkerKey
    ) {
        ManagedGroup group;
        PreparedReplica replica;
        synchronized (this) {
            ensureControllable();
            group = requireManagedGroup(workerGroupId);
            replica = requireReplica(group, labWorkerKey);
        }
        stopBusiness(replica);
        group.manager().stop(replica.replicaKey());
    }

    void stopWorkers(List<WorkerSimulatorCoordinate> coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        List<WorkerStopTarget> targets = new ArrayList<>(coordinates.size());
        synchronized (this) {
            ensureControllable();
            for (WorkerSimulatorCoordinate coordinate : coordinates) {
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
                        replica
                ));
            }
        }
        RuntimeException failure = null;
        for (WorkerStopTarget target : targets) {
            try {
                stopBusiness(target.replica());
                target.manager().stop(target.replica().replicaKey());
            } catch (RuntimeException error) { failure = accumulate(failure, error); }
        }
        if (failure != null) throw failure;
    }

    private void stopBusiness(PreparedReplica replica) {
        if (replica.sim != null) sms.registry.stop(replica.sim);
        if (replica.sender != null) messages.stop(replica.sender);
    }

    void replaceWorkerState(
            String workerGroupId,
            String labWorkerKey,
            String encodedDocument
    ) {
        WorkerSimulatorCoordinate target = beginPropertiesOperation(workerGroupId, labWorkerKey);
        try {
            synchronized (this) {
                ensureControllable();
                ManagedGroup group = requireManagedGroup(workerGroupId);
                PreparedReplica replica = requireReplica(group, labWorkerKey);
                applyProperties(group, replica, replica.stateFile().parseReplacement(encodedDocument), true);
            }
        } finally {
            propertiesOperations.remove(target);
        }
    }

    boolean publishProperties(
            String workerGroupId,
            String labWorkerKey,
            Map<String, String> properties,
            boolean replace
    ) {
        WorkerSimulatorCoordinate target = beginPropertiesOperation(workerGroupId, labWorkerKey);
        try {
            Map<String, String> supplied = Map.copyOf(properties);
            JavaWorkerManager manager;
            synchronized (this) {
                ensureControllable();
                ManagedGroup group = requireManagedGroup(workerGroupId);
                PreparedReplica replica = requireReplica(group, labWorkerKey);
                manager = group.manager();
                if (!manager.desiredRunning(labWorkerKey)
                        || manager.snapshot(labWorkerKey).state() != WorkerLifecycle.State.RUNNING) {
                    throw new IllegalStateException("Worker must be running to publish Properties");
                }
                Map<String, String> complete = new LinkedHashMap<>();
                if (!replace) {
                    complete.putAll(replica.stateFile().workerProperties());
                }
                complete.putAll(supplied);
                Map<String, String> validated = replica.stateFile().parseReplacement(Jsons.toJson(Map.of(
                        "schemaVersion", 2,
                        "workerProperties", complete
                )));
                applyProperties(group, replica, validated, true);
            }
            // Keep the per-Worker gate while the SDK reads/sends, without holding the
            // inventory gate. Stop and shutdown may revoke this run concurrently.
            return replace ? manager.reportProperties(labWorkerKey)
                    : manager.reportProperties(labWorkerKey, supplied);
        } finally {
            propertiesOperations.remove(target);
        }
    }

    private WorkerSimulatorCoordinate beginPropertiesOperation(String groupId, String key) {
        WorkerSimulatorCoordinate target = new WorkerSimulatorCoordinate(groupId, key);
        if (!propertiesOperations.add(target)) {
            throw new IllegalStateException("Worker Properties operation is already in progress");
        }
        return target;
    }

    /** Called under the inventory gate, never from a Handler or business gate. */
    private void applyProperties(ManagedGroup group, PreparedReplica replica,
            Map<String, String> replacement, boolean persist) {
        if (usesNumbers(group.preparedGroup().group().config())) {
            validateNumberProperties(replacement);
            String phone = replacement.get("phone");
            for (ManagedGroup other : managedGroups) {
                if (!usesNumbers(other.preparedGroup().group().config())) continue;
                for (PreparedReplica candidate : other.preparedGroup().replicas()) {
                    if (candidate != replica && phone.equals(candidate.properties().get("phone")))
                        throw new IllegalArgumentException("Phone is already assigned to another Worker");
                }
            }
        }
        if (persist) replica.stateFile().persist(replacement);
        Runnable install = () -> replica.stateFile().install(replacement);
        if (replica.sim != null) sms.registry.updateProperties(replica.sim, replacement, install);
        else install.run();
    }

    private static void validateNumberProperties(Map<String, String> properties) {
        String phone = properties.get("phone"), country = properties.get("country");
        if (phone == null || phone.isBlank() || country == null || !country.matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Number capabilities require phone and uppercase two-letter country");
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

    synchronized WorkerSimulatorCommandCheckpoints.Snapshot
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

    private List<PreparedGroup> prepareGroups(WorkerSimulatorStartupPlan startupPlan) {
        List<WorkerSimulatorGroupConfig> configs = groups.stream()
                .map(GroupAssembly::config)
                .toList();
        List<WorkerSimulatorLab.DiscoveredGroup> discovered =
                lab.prepare(configs, world -> validateWorld(world, startupPlan));
        if (discovered.size() != groups.size()) {
            throw new IllegalStateException(
                    "Worker Simulator returned incomplete groups"
            );
        }

        List<PreparedGroup> preparedGroups = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            GroupAssembly group = groups.get(index);
            WorkerSimulatorLab.DiscoveredGroup discoveredGroup =
                    discovered.get(index);
            if (!group.config().equals(discoveredGroup.config())) {
                throw new IllegalStateException(
                        "Worker Simulator changed WorkerGroup order"
                );
            }
            if (discoveredGroup.workers().isEmpty()) {
                continue;
            }
            List<PreparedReplica> replicas = new ArrayList<>();
            for (WorkerSimulatorStateFile worker
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

    private static void validateWorld(List<WorkerSimulatorLab.DiscoveredGroup> world,
            WorkerSimulatorStartupPlan startupPlan) {
        Set<String> phones = new HashSet<>();
        Set<WorkerSimulatorCoordinate> coordinates = new HashSet<>();
        for (WorkerSimulatorLab.DiscoveredGroup group : world) {
            for (WorkerSimulatorStateFile worker : group.workers()) {
                coordinates.add(new WorkerSimulatorCoordinate(group.config().workerGroupId(), worker.labWorkerKey()));
                if (usesNumbers(group.config())) {
                    validateNumberProperties(worker.workerProperties());
                    if (!phones.add(worker.workerProperties().get("phone"))) {
                        throw new IllegalArgumentException("Duplicate inventory phone");
                    }
                }
            }
        }
        for (WorkerSimulatorCoordinate initial : startupPlan.initialWorkers()) {
            if (!coordinates.contains(initial)) throw new IllegalArgumentException("startup plan references an unknown Worker");
        }
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

    private List<WorkerSimulatorCoordinate> resolveInitialWorkers(
            WorkerSimulatorStartupPlan startupPlan
    ) {
        if (startupPlan.startAll()) {
            List<WorkerSimulatorCoordinate> coordinates = new ArrayList<>();
            for (ManagedGroup group : managedGroups) {
                for (PreparedReplica replica
                        : group.preparedGroup().replicas()) {
                    coordinates.add(coordinate(group, replica));
                }
            }
            return List.copyOf(coordinates);
        }
        for (WorkerSimulatorCoordinate worker
                : startupPlan.initialWorkers()) {
            ManagedGroup group = requireManagedGroup(worker.workerGroupId());
            requireReplica(group, worker.labWorkerKey());
        }
        return startupPlan.initialWorkers();
    }

    private RuntimeException startWorkers(
            List<WorkerSimulatorCoordinate> initialWorkers
    ) {
        RuntimeException failure = null;
        Set<WorkerSimulatorCoordinate> selected = new HashSet<>(
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
                ).add(replica.replicaKey());
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
                replica.replicaKey()
        );
        return new WorkerControlSnapshot(
                group.preparedGroup().group().config().workerGroupId(),
                replica.replicaKey(),
                manager.desiredRunning(replica.replicaKey()),
                runtime,
                includeProperties
                        ? replica.stateFile().workerProperties()
                        : null
        );
    }

    private ManagedGroup requireManagedGroup(String workerGroupId) {
        WorkerSimulatorGroupConfig.requireNonBlank(
                workerGroupId,
                "workerGroupId"
        );
        ManagedGroup group = managedGroupsById.get(workerGroupId);
        if (group == null) {
            throw new UnknownWorkerException(
                    "Unknown Worker Simulator Group: " + workerGroupId
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
            if (replica.replicaKey().equals(key)) {
                return replica;
            }
        }
        throw new UnknownWorkerException(
                "Unknown Worker Simulator: " + key
        );
    }

    private String requireLabWorkerKey(
            String workerGroupId,
            String labWorkerKey
    ) {
        ManagedGroup group = requireManagedGroup(workerGroupId);
        WorkerSimulatorGroupConfig.requireNonBlank(
                labWorkerKey,
                "labWorkerKey"
        );
        boolean present = group.preparedGroup().replicas().stream()
                .anyMatch(replica -> replica.replicaKey()
                        .equals(labWorkerKey));
        if (!present) {
            throw new UnknownWorkerException(
                    "Unknown Worker Simulator: " + labWorkerKey
            );
        }
        return labWorkerKey;
    }

    private void ensureControllable() {
        if (!started || closed) {
            throw new IllegalStateException(
                    "Worker Simulator is not running"
            );
        }
    }

    private JavaWorkerManager createManager(
            URI runtimeApiBaseUrl,
            PreparedGroup preparedGroup
    ) {
        GroupAssembly group = preparedGroup.group();
        WorkerSimulatorGroupConfig config = group.config();
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
        builder.batchWorkerKind("SCENARIO_LAB");
        AtomicReference<JavaWorkerManager> managerReference = new AtomicReference<>();
        for (PreparedReplica replica : preparedGroup.replicas()) {
            List<WorkerEventDefinition<?>> extensions = new ArrayList<>();
            if (usesSms(config)) {
                replica.sim = sms.registry.addSim(config.workerGroupId(), replica.replicaKey(), replica::properties,
                        () -> managerReference.get().snapshot(replica.replicaKey()).workerId(),
                        () -> managerReference.get().snapshot(replica.replicaKey()).state().name(),
                        () -> managerReference.get().desiredRunning(replica.replicaKey()));
                extensions.addAll(sms.definitions(replica.sim).stream()
                        .filter(definition -> config.events().contains(definition.eventName())).toList());
            }
            if (usesMessages(config)) {
                replica.sender = messages.addSender(config.workerGroupId(), replica.replicaKey(), replica::properties,
                        () -> managerReference.get().snapshot(replica.replicaKey()).workerId(),
                        () -> managerReference.get().snapshot(replica.replicaKey()).state().name());
                extensions.addAll(messages.definitions(replica.sender));
            }
            if (config.events().contains(WorkerSimulatorExecutionWitnesses.EVENT))
                extensions.add(executionWitnesses.definition(config.workerGroupId(), replica.replicaKey()));
            builder.replica(
                    replica.replicaKey(),
                    replica::properties,
                    extensions
            );
        }
        JavaWorkerManager manager = builder.build();
        managerReference.set(manager);
        return manager;
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
            WorkerSimulatorCommandCheckpoints checkpoints
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
                WorkerSimulatorLabEvents.backgroundFaults()
        );
        addDefinitionExtensions(
                definitions,
                List.of(WorkerSimulatorLabEvents.checkpoint(checkpoints))
        );
        return Collections.unmodifiableMap(definitions);
    }

    private static WorkerSimulatorCoordinate coordinate(
            ManagedGroup group,
            PreparedReplica replica
    ) {
        return new WorkerSimulatorCoordinate(
                group.preparedGroup().group().config().workerGroupId(),
                replica.replicaKey()
        );
    }

    private static void requireCheckpointCapable(ManagedGroup group) {
        boolean supported = group.preparedGroup()
                .group()
                .definitionExtensions()
                .stream()
                .anyMatch(definition -> WorkerSimulatorLabEvents
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
                        "Duplicate Worker Simulator eventCode: "
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
            List<WorkerSimulatorGroupConfig> configs,
            Map<String, WorkerEventDefinition<?>>
                    availableExtensionsByEventCode
    ) {
        Objects.requireNonNull(configs, "configs");
        List<GroupAssembly> resolved = new ArrayList<>(configs.size());
        for (WorkerSimulatorGroupConfig config : configs) {
            List<WorkerEventDefinition<?>> definitionExtensions =
                    new ArrayList<>();
            for (String eventCode : config.events()) {
                if (Set.of(WorkerSimulatorExecutionWitnesses.EVENT, SmsScenario.START_EVENT,
                        SmsScenario.CANCEL_EVENT, MessageScenario.SEND_EVENT).contains(eventCode)) {
                    continue; // This finite capability is bound to each actual replica at construction.
                }
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

    Map<String, Object> executionWitnesses(long after, int limit) {
        return executionWitnesses.read(after, limit);
    }

    private static boolean usesSms(WorkerSimulatorGroupConfig config) {
        return config.events().contains(SmsScenario.START_EVENT) || config.events().contains(SmsScenario.CANCEL_EVENT);
    }
    private static boolean usesMessages(WorkerSimulatorGroupConfig config) {
        return config.events().contains(MessageScenario.SEND_EVENT);
    }
    private static boolean usesNumbers(WorkerSimulatorGroupConfig config) { return usesSms(config) || usesMessages(config); }

    @FunctionalInterface
    interface GroupManagerFactory {

        JavaWorkerManager create(
                URI runtimeApiBaseUrl,
                PreparedGroup preparedGroup
        );
    }

    record GroupAssembly(
            WorkerSimulatorGroupConfig config,
            List<WorkerEventDefinition<?>> definitionExtensions
    ) {
    }

    static final class PreparedReplica {
        private final String replicaKey;
        private final WorkerSimulatorStateFile stateFile;
        ListeningRegistry.Sim sim;
        MessageScenario.Sender sender;
        PreparedReplica(String replicaKey, WorkerSimulatorStateFile stateFile) {
            this.replicaKey = replicaKey; this.stateFile = stateFile;
        }
        String replicaKey() { return replicaKey; }
        WorkerSimulatorStateFile stateFile() { return stateFile; }
        Map<String, String> properties() { return stateFile.workerProperties(); }
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
            PreparedReplica replica
    ) {
    }
}
