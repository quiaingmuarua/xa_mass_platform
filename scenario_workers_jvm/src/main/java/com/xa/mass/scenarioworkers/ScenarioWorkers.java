package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.scenarioworkers.sms.SmsScenario;
import com.xa.mass.scenarioworkers.messaging.MessageScenario;
import java.time.Duration;
import java.util.Locale;
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

public final class ScenarioWorkers implements AutoCloseable {

    private static final int WORKER_START_FAILED = 14004;
    private static final String EXTENSION_WORKER_EVENT_PREFIX =
            "extension.worker.";

    private final URI runtimeApiBaseUrl;
    private final List<GroupAssembly> groups;
    private final ScenarioWorkerLab lab;
    private final SmsScenario sms;
    private final MessageScenario messages;
    private final String scenario;
    private final int[] smsCounts;
    private final ScenarioWorkerCommandCheckpoints commandCheckpoints;
    private final ScenarioWorkerExecutionWitnesses executionWitnesses;
    private final GroupManagerFactory groupManagerFactory;
    private final List<ManagedGroup> managedGroups = new ArrayList<>();
    private final Map<String, ManagedGroup> managedGroupsById =
            new LinkedHashMap<>();
    private final Set<ScenarioWorkerCoordinate> propertiesOperations =
            ConcurrentHashMap.newKeySet();

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
            ScenarioWorkerCommandCheckpoints commandCheckpoints,
            ScenarioWorkerExecutionWitnesses executionWitnesses
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
        sms = null;
        messages = null;
        scenario = "lab";
        smsCounts = null;
        this.groupManagerFactory = Objects.requireNonNull(
                groupManagerFactory,
                "groupManagerFactory"
        );
        this.commandCheckpoints = Objects.requireNonNull(
                commandCheckpoints,
                "commandCheckpoints"
        );
        this.executionWitnesses = Objects.requireNonNull(executionWitnesses, "executionWitnesses");
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
            ScenarioWorkerExecutionWitnesses witnesses = new ScenarioWorkerExecutionWitnesses();
            return new ScenarioWorkers(
                    runtimeApiBaseUrl,
                    sandboxRoot,
                    configs,
                    availableDefinitionExtensions(checkpoints),
                    (uri, group) -> createManager(uri, group, witnesses, null, null),
                    checkpoints,
                    witnesses
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

    static ScenarioWorkers sms(URI runtimeApiBaseUrl, int[] counts) {
        return new ScenarioWorkers(runtimeApiBaseUrl, counts, null);
    }

    static ScenarioWorkers products(URI runtimeApiBaseUrl, int[] counts, String scenario) {
        return new ScenarioWorkers(runtimeApiBaseUrl, counts, null, scenario);
    }

    ScenarioWorkers(URI runtimeApiBaseUrl, int[] counts, GroupManagerFactory managerFactory) {
        this(runtimeApiBaseUrl, counts, managerFactory, "sms");
    }

    private ScenarioWorkers(URI runtimeApiBaseUrl, int[] counts, GroupManagerFactory managerFactory, String scenario) {
        this.runtimeApiBaseUrl = Objects.requireNonNull(runtimeApiBaseUrl, "runtimeApiBaseUrl");
        if (counts == null || counts.length != 3 || java.util.Arrays.stream(counts).anyMatch(n -> n < 1)
                || java.util.Arrays.stream(counts).asLongStream().sum() > 10_000)
            throw new IllegalArgumentException("SMS counts require three positive counts, total <= 10000");
        smsCounts = counts.clone();
        lab = null;
        this.scenario = scenario;
        sms = !scenario.equals("messages") ? new SmsScenario() : null;
        messages = !scenario.equals("sms") ? new MessageScenario() : null;
        commandCheckpoints = new ScenarioWorkerCommandCheckpoints();
        executionWitnesses = new ScenarioWorkerExecutionWitnesses();
        var definitions = StringUtilityWorkerEvents.definitions();
        List<String> eventCodes = new ArrayList<>(definitions.stream().map(WorkerEventDefinition::eventName).toList());
        if (sms != null) {
            eventCodes.add("extension.worker.sms.listen.start");
            eventCodes.add("extension.worker.sms.listen.cancel");
        }
        if (messages != null) eventCodes.add("extension.worker.message.send");
        String prefix = scenario.equals("products") ? "demo-" : scenario.equals("messages") ? "messages-" : "sms-";
        groups = List.of("CN", "US", "GB").stream().map(country -> new GroupAssembly(
                new ScenarioWorkerGroupConfig(prefix + country.toLowerCase(Locale.ROOT), eventCodes,
                        Duration.ofSeconds(10), TextMessageReconnectPolicy.defaults()), definitions)).toList();
        groupManagerFactory = managerFactory != null ? managerFactory
                : (uri, group) -> createManager(uri, group, executionWitnesses, sms, messages);
    }

    boolean isSms() { return sms != null; }
    boolean isGenerated() { return lab == null; }
    boolean hasMessages() { return messages != null; }
    String scenario() { return scenario; }
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
                if (isGenerated()) {
                    for (ManagedGroup group : managedGroups) group.manager().start();
                    initialWorkerCount = preparedGroups.stream().mapToInt(group -> group.replicas().size()).sum();
                } else {
                List<ScenarioWorkerCoordinate> initialWorkers =
                        resolveInitialWorkers(startupPlan);
                RuntimeException startFailure = startWorkers(initialWorkers);
                if (startFailure != null) {
                    throw startFailure;
                }
                initialWorkerCount = initialWorkers.size();
                }
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
        if (!isGenerated()) {
            group.manager().prepareAndStart(List.of(replicaKey));
        } else {
            String phone = requireReplica(group, replicaKey).properties().get("phone");
            if (sms != null) sms.registry.beginStart(phone);
            if (messages != null) messages.start(phone);
            try {
                group.manager().start(replicaKey);
            } catch (RuntimeException | Error failure) {
                if (sms != null) sms.registry.stop(phone);
                if (messages != null) messages.stop(phone);
                throw failure;
            } finally {
                try {
                    // Stop does not wait for a slow start. Revoke an overtaken start when it returns.
                    if (sms != null && !sms.registry.startStillRequested(phone)) group.manager().stop(replicaKey);
                } finally { if (sms != null) sms.registry.endStart(phone); }
            }
        }
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
        if (sms != null) sms.registry.stop(requireReplica(group, replicaKey).properties().get("phone"));
        if (messages != null) messages.stop(requireReplica(group, replicaKey).properties().get("phone"));
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
                        replica.replicaKey()
                ));
            }
        }
        for (WorkerStopTarget target : targets) {
            target.manager().stop(target.replicaKey());
        }
    }

    void replaceWorkerState(
            String workerGroupId,
            String labWorkerKey,
            String encodedDocument
    ) {
        ScenarioWorkerCoordinate target = beginPropertiesOperation(workerGroupId, labWorkerKey);
        try {
            synchronized (this) {
                ensureControllable();
                ManagedGroup group = requireManagedGroup(workerGroupId);
                requireReplica(group, labWorkerKey).stateFile().replace(encodedDocument);
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
        ScenarioWorkerCoordinate target = beginPropertiesOperation(workerGroupId, labWorkerKey);
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
                replica.stateFile().replace(Jsons.toJson(Map.of(
                        "schemaVersion", 2,
                        "workerProperties", complete
                )));
            }
            // Keep the per-Worker gate while the SDK reads/sends, without holding the
            // inventory gate. Stop and shutdown may revoke this run concurrently.
            return replace ? manager.reportProperties(labWorkerKey)
                    : manager.reportProperties(labWorkerKey, supplied);
        } finally {
            propertiesOperations.remove(target);
        }
    }

    private ScenarioWorkerCoordinate beginPropertiesOperation(String groupId, String key) {
        ScenarioWorkerCoordinate target = new ScenarioWorkerCoordinate(groupId, key);
        if (!propertiesOperations.add(target)) {
            throw new IllegalStateException("Worker Properties operation is already in progress");
        }
        return target;
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
        if (isGenerated()) return prepareSmsGroups();
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
                        worker,
                        null
                ));
            }
            preparedGroups.add(new PreparedGroup(
                    group,
                    List.copyOf(replicas)
            ));
        }
        return List.copyOf(preparedGroups);
    }

    private List<PreparedGroup> prepareSmsGroups() {
        List<PreparedGroup> prepared = new ArrayList<>();
        String[] countries = {"CN", "US", "GB"};
        String[] prefixes = {"+861700", "+120255", "+447700"};
        for (int countryIndex = 0; countryIndex < countries.length; countryIndex++) {
            String country = countries[countryIndex];
            List<PreparedReplica> replicas = new ArrayList<>();
            for (int index = 0; index < smsCounts[countryIndex]; index++) {
                String phone = prefixes[countryIndex] + String.format(Locale.ROOT, "%06d", index);
                Map<String, String> properties = new LinkedHashMap<>(Map.of("phone", phone, "country", country,
                        "operator", "Preview SIM", "simulated", "true"));
                if (messages != null) properties.put("messaging.enabled", "true");
                replicas.add(new PreparedReplica(country + "-" + index, null, Map.copyOf(properties)));
            }
            prepared.add(new PreparedGroup(groups.get(countryIndex), List.copyOf(replicas)));
        }
        return List.copyOf(prepared);
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
            if (replica.replicaKey().equals(key)) {
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
                .anyMatch(replica -> replica.replicaKey()
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
            PreparedGroup preparedGroup,
            ScenarioWorkerExecutionWitnesses witnesses,
            SmsScenario sms,
            MessageScenario messages
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
        if (sms == null && messages == null) builder.batchWorkerKind("SCENARIO_LAB");
        AtomicReference<JavaWorkerManager> managerReference = new AtomicReference<>();
        for (PreparedReplica replica : preparedGroup.replicas()) {
            List<WorkerEventDefinition<?>> extensions;
            if (sms != null || messages != null) {
                Map<String, String> properties = replica.properties();
                extensions = new ArrayList<>();
                if (sms != null) {
                var sim = sms.registry.addSim(config.workerGroupId(), replica.replicaKey(),
                        properties.get("phone"), properties.get("country"),
                        () -> managerReference.get().snapshot(replica.replicaKey()).workerId(),
                        () -> managerReference.get().snapshot(replica.replicaKey()).state().name(),
                        () -> managerReference.get().desiredRunning(replica.replicaKey()));
                extensions.addAll(sms.definitions(sim));
                }
                if (messages != null) {
                    var sender = messages.addSender(config.workerGroupId(), replica.replicaKey(), properties.get("country"), properties.get("phone"),
                            () -> managerReference.get().snapshot(replica.replicaKey()).workerId(),
                            () -> managerReference.get().snapshot(replica.replicaKey()).state().name());
                    extensions.addAll(messages.definitions(sender));
                }
            } else {
                extensions = config.eventCodes().contains(ScenarioWorkerExecutionWitnesses.EVENT)
                        ? List.of(witnesses.definition(config.workerGroupId(), replica.replicaKey())) : List.of();
            }
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
                replica.replicaKey()
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
                if (ScenarioWorkerExecutionWitnesses.EVENT.equals(eventCode)) {
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
            String replicaKey,
            ScenarioWorkerStateFile stateFile,
            Map<String, String> generatedProperties
    ) {
        Map<String, String> properties() {
            return stateFile != null ? stateFile.workerProperties() : generatedProperties;
        }
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
