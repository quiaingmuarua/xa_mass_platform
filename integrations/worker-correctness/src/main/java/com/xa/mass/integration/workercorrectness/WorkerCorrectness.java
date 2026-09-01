package com.xa.mass.integration.workercorrectness;

import com.xa.mass.integration.workercorrectness.CorrectnessOptions.Phase;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.CallStatus;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.DirectCallOutcome;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.TaskItem;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.TargetOutcome;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkerCorrectness {

    private static final String WORKER_PROBE_EVENT =
            "platform.worker.probe";
    private static final String WORKER_PROPERTIES_EVENT =
            "platform.worker.properties.snapshot";
    private static final String ADAPTER_PROPERTIES_EVENT =
            "platform.adapter.worker-properties.snapshot";
    private static final List<Integer> EVENT_ITEM_COUNTS = List.of(17, 17, 16);
    private static final List<CapabilityGroup> CAPABILITY_GROUPS = List.of(
            new CapabilityGroup(
                    "scenario-phone-number-workers",
                    "rawNumber",
                    List.of(
                            "extension.worker.phonenumber.e164",
                            "extension.worker.phonenumber.country",
                            "extension.worker.phonenumber.original-carrier"
                    )
            ),
            new CapabilityGroup(
                    "scenario-string-utils-workers",
                    "value",
                    List.of(
                            "extension.worker.string.md5",
                            "extension.worker.string.sha1",
                            "extension.worker.string.base64.encode"
                    )
            )
    );

    private WorkerCorrectness() {
    }

    static void execute(CorrectnessOptions options) throws IOException {
        Phase phase = options.requiredPhase();
        CorrectnessSpec spec = CorrectnessSpec.load(options.correctnessSpec());
        CorrectnessEvidence evidence = new CorrectnessEvidence(
                options.proofId(),
                phase,
                spec
        );
        RuntimeException proofFailure = null;
        try {
            run(options, phase, spec, evidence);
        } catch (ProofFailure error) {
            evidence.failure(
                    error.invariant,
                    error.groupId,
                    error.getMessage(),
                    error.missingIds,
                    error.unexpectedIds,
                    error.inconsistentIds
            );
            proofFailure = error;
        } catch (RuntimeException error) {
            evidence.failure(
                    "worker-correctness.acceptance",
                    null,
                    safeMessage(error),
                    List.of(),
                    List.of(),
                    List.of()
            );
            proofFailure = error;
        } catch (IOException error) {
            evidence.failure(
                    "worker-correctness.acceptance-io",
                    null,
                    "Worker Correctness proof could not read required evidence",
                    List.of(),
                    List.of(),
                    List.of()
            );
            proofFailure = new IllegalStateException(
                    "Worker Correctness proof could not read required evidence",
                    error
            );
        }

        try {
            evidence.write(options.evidenceFile());
        } catch (IOException | RuntimeException writeFailure) {
            if (proofFailure != null) {
                proofFailure.addSuppressed(writeFailure);
                throw proofFailure;
            }
            throw writeFailure;
        }
        if (proofFailure != null) {
            throw proofFailure;
        }
    }

    private static void run(
            CorrectnessOptions options,
            Phase phase,
            CorrectnessSpec spec,
            CorrectnessEvidence evidence
    ) throws IOException {
        Duration maximumWait = Duration.ofMillis(
                options.maximumWaitMillis()
        );
        RuntimeApiClient api = new RuntimeApiClient(
                options.serverBaseUrl(),
                Duration.ofMillis(options.requestTimeoutMillis())
        );
        Map<String, Map<String, String>> inventory;
        try {
            inventory = ScenarioWorkerInventory.await(
                        options.scenarioWorkerLabRoot(),
                        spec,
                        api,
                        maximumWait
                );
        } catch (ScenarioWorkerInventory.InventoryMismatch error) {
            throw new ProofFailure(
                    error.invariant(),
                    error.groupId(),
                    error.getMessage(),
                    error.missingIds(),
                    error.unexpectedIds(),
                    error.inconsistentIds()
            );
        }
        evidence.inventory(inventory);

        if (phase == Phase.RESTART) {
            Map<String, Map<String, String>> baseline = loadBaseline(
                    options.baselineFile(phase),
                    spec,
                    options.proofId()
            );
            verifyBaseline(inventory, baseline, evidence);
        }

        awaitConnected(api, spec, inventory, evidence, maximumWait);
        verifyProbe(api, spec, inventory, evidence);
        verifyProperties(api, spec, inventory, evidence);
        if (phase == Phase.INITIAL) {
            verifyCapabilities(api, options, evidence);
        }
    }

    private static void verifyCapabilities(
            RuntimeApiClient api,
            CorrectnessOptions options,
            CorrectnessEvidence evidence
    ) throws IOException {
        Map<String, List<String>> seeds = Map.of(
                "scenario-phone-number-workers",
                readSeed(options.phoneSeedPath(), "phone seed"),
                "scenario-string-utils-workers",
                readSeed(options.stringSeedPath(), "string seed")
        );
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        int submittedCount = 0;
        int succeededCount = 0;
        for (CapabilityGroup group : CAPABILITY_GROUPS) {
            List<TaskItem> items = new ArrayList<>();
            List<String> inputs = seeds.get(group.workerGroupId());
            int inputIndex = 0;
            for (int eventIndex = 0;
                    eventIndex < group.eventCodes().size();
                    eventIndex++) {
                String eventCode = group.eventCodes().get(eventIndex);
                int count = EVENT_ITEM_COUNTS.get(eventIndex);
                eventCounts.put(group.workerGroupId() + "/" + eventCode, count);
                for (int itemIndex = 0; itemIndex < count; itemIndex++) {
                    String messageId = "correctness-"
                            + normalizeEvent(options.proofId())
                            + "-"
                            + normalizeEvent(group.workerGroupId())
                            + "-"
                            + normalizeEvent(eventCode)
                            + "-"
                            + String.format("%03d", itemIndex + 1);
                    items.add(new TaskItem(
                            messageId,
                            eventCode,
                            Map.of(
                                    group.payloadName(),
                                    inputs.get(inputIndex++ % inputs.size())
                            )
                    ));
                }
            }
            if (items.size() != 50) {
                throw new IllegalStateException(
                        "Worker correctness Group must contain 50 Items"
                );
            }
            Map<String, CallStatus> results = api.callItems(
                    group.workerGroupId(),
                    items,
                    Math.min(options.maximumWaitMillis(), 60_000L)
            );
            Set<String> expected = items.stream()
                    .map(TaskItem::messageId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> succeeded = results.entrySet().stream()
                    .filter(entry -> entry.getValue() == CallStatus.SUCCEEDED)
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!results.keySet().equals(expected)
                    || !succeeded.equals(expected)) {
                throw mismatch(
                        "capability.result-identities",
                        group.workerGroupId(),
                        "Worker correctness calls did not all succeed",
                        expected,
                        succeeded,
                        results.keySet()
                );
            }
            submittedCount += items.size();
            succeededCount += succeeded.size();
        }
        if (submittedCount != 100
                || succeededCount != 100
                || eventCounts.size() != 6) {
            throw new IllegalStateException(
                    "Worker correctness requires 100 successful calls across six events"
            );
        }
        evidence.capabilityResults(
                submittedCount,
                succeededCount,
                eventCounts
        );
    }

    private static List<String> readSeed(Path path, String label)
            throws IOException {
        List<String> values = Files.readAllLines(path, StandardCharsets.UTF_8)
                .stream()
                .filter(value -> !value.isBlank())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return values;
    }

    private static String normalizeEvent(String eventCode) {
        return eventCode.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static void verifyBaseline(
            Map<String, Map<String, String>> inventory,
            Map<String, Map<String, String>> baseline,
            CorrectnessEvidence evidence
    ) {
        evidence.baselineIdentityMatched(inventory.equals(baseline));
        for (String groupId : baseline.keySet()) {
            Map<String, String> expected = baseline.get(groupId);
            Map<String, String> actual = inventory.get(groupId);
            if (expected.equals(actual)) {
                continue;
            }
            Set<String> expectedIds = new LinkedHashSet<>(expected.values());
            Set<String> actualIds = new LinkedHashSet<>(actual.values());
            ProofFailure failure = mismatch(
                    "restart.identity-mapping",
                    groupId,
                    "Worker identity mapping changed after restart",
                    expectedIds,
                    actualIds
            );
            List<String> inconsistentKeys = expected.keySet().stream()
                    .filter(key -> !expected.get(key).equals(actual.get(key)))
                    .toList();
            throw failure.withInconsistentIds(inconsistentKeys);
        }
    }

    private static void awaitConnected(
            RuntimeApiClient api,
            CorrectnessSpec spec,
            Map<String, Map<String, String>> inventory,
            CorrectnessEvidence evidence,
            Duration maximumWait
    ) {
        List<String> expectedIds = allWorkerIds(spec, inventory);
        long deadline = System.nanoTime() + maximumWait.toNanos();
        RuntimeException latestFailure = null;
        Map<String, String> latestStates = Map.of();
        while (System.nanoTime() < deadline) {
            try {
                latestStates = api.observeNetwork(
                        spec.endpointManagerId(),
                        expectedIds
                );
                recordConnected(spec, inventory, latestStates, evidence);
                if (latestStates.keySet().equals(
                        new LinkedHashSet<>(expectedIds)
                ) && latestStates.values().stream()
                        .allMatch("connected"::equals)) {
                    return;
                }
            } catch (RuntimeException error) {
                latestFailure = error;
            }
            sleepForObservation();
        }
        Set<String> connected = new LinkedHashSet<>();
        latestStates.forEach((workerId, state) -> {
            if ("connected".equals(state)) {
                connected.add(workerId);
            }
        });
        ProofFailure failure = mismatch(
                "network.connected-identities",
                null,
                "Worker Network identities did not become connected",
                new LinkedHashSet<>(expectedIds),
                connected,
                latestStates.keySet()
        );
        if (latestFailure != null) {
            failure.addSuppressed(latestFailure);
        }
        throw failure;
    }

    private static void recordConnected(
            CorrectnessSpec spec,
            Map<String, Map<String, String>> inventory,
            Map<String, String> states,
            CorrectnessEvidence evidence
    ) {
        for (String groupId : spec.labWorkerKeysByGroup().keySet()) {
            List<String> connected = inventory.get(groupId).values().stream()
                    .filter(workerId -> "connected".equals(
                            states.get(workerId)
                    ))
                    .toList();
            evidence.connected(groupId, connected);
        }
    }

    private static void verifyProbe(
            RuntimeApiClient api,
            CorrectnessSpec spec,
            Map<String, Map<String, String>> inventory,
            CorrectnessEvidence evidence
    ) {
        for (String groupId : spec.labWorkerKeysByGroup().keySet()) {
            List<String> expectedIds = List.copyOf(
                    inventory.get(groupId).values()
            );
            DirectCallOutcome call = api.callWorkers(
                    spec.endpointManagerId(),
                    groupId,
                    expectedIds,
                    WORKER_PROBE_EVENT
            );
            List<String> observed = successfulIds(call, expectedIds);
            evidence.probeObserved(groupId, observed);
            if (!"observed".equals(call.status())
                    || !new LinkedHashSet<>(observed).equals(
                    new LinkedHashSet<>(expectedIds))
                    || !call.results().keySet().equals(
                    new LinkedHashSet<>(expectedIds))) {
                throw mismatch(
                        "probe.observed-identities",
                        groupId,
                        "Worker probe Results do not match target identities",
                        new LinkedHashSet<>(expectedIds),
                        new LinkedHashSet<>(observed),
                        call.results().keySet()
                );
            }
        }
    }

    private static void verifyProperties(
            RuntimeApiClient api,
            CorrectnessSpec spec,
            Map<String, Map<String, String>> inventory,
            CorrectnessEvidence evidence
    ) {
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (String groupId : spec.labWorkerKeysByGroup().keySet()) {
            List<String> expectedIds = List.copyOf(
                    inventory.get(groupId).values()
            );
            DirectCallOutcome call = api.callWorkers(
                    spec.endpointManagerId(),
                    groupId,
                    expectedIds,
                    WORKER_PROPERTIES_EVENT
            );
            Set<String> successful = new LinkedHashSet<>(successfulIds(
                    call,
                    expectedIds
            ));
            if (!"observed".equals(call.status())
                    || !successful.equals(new LinkedHashSet<>(expectedIds))
                    || !call.results().keySet().equals(
                    new LinkedHashSet<>(expectedIds))) {
                throw mismatch(
                        "properties.worker-snapshot-identities",
                        groupId,
                        "Worker Properties Results do not match targets",
                        new LinkedHashSet<>(expectedIds),
                        successful,
                        call.results().keySet()
                );
            }
            for (String workerId : expectedIds) {
                Map<String, Object> payload = Jsons.parseObject(
                        call.results().get(workerId).opaqueResultPayload()
                );
                snapshots.put(workerId, RuntimeApiClient.objectMap(
                        payload.get("properties"),
                        "Worker Properties snapshot"
                ));
            }
        }

        List<String> allIds = allWorkerIds(spec, inventory);
        TargetOutcome adapter = api.callAdapter(
                spec.endpointManagerId(),
                ADAPTER_PROPERTIES_EVENT,
                Jsons.toJson(Map.of("workerIds", allIds))
        );
        if (!adapter.successful()) {
            throw new ProofFailure(
                    "properties.adapter-snapshot",
                    null,
                    "Adapter Properties snapshot was not observed",
                    allIds,
                    List.of(),
                    List.of()
            );
        }
        Map<String, Object> adapterPayload = Jsons.parseObject(
                adapter.opaqueResultPayload()
        );
        Map<String, Object> observations = RuntimeApiClient.objectMap(
                adapterPayload.get("propertiesByWorkerId"),
                "Adapter Properties observations"
        );
        List<String> matched = new ArrayList<>();
        for (String workerId : allIds) {
            Object rawObservation = observations.get(workerId);
            if (rawObservation == null) {
                continue;
            }
            Map<String, Object> observation = RuntimeApiClient.objectMap(
                    rawObservation,
                    "Adapter Worker Properties observation"
            );
            Object updatedAt = observation.get("updatedAtMillis");
            Map<String, Object> cached = RuntimeApiClient.objectMap(
                    observation.get("properties"),
                    "Adapter cached Worker Properties"
            );
            if (updatedAt instanceof Number
                    && cached.equals(snapshots.get(workerId))) {
                matched.add(workerId);
            }
        }
        for (String groupId : spec.labWorkerKeysByGroup().keySet()) {
            List<String> groupMatched = inventory.get(groupId).values().stream()
                    .filter(matched::contains)
                    .toList();
            evidence.propertiesMatched(groupId, groupMatched);
        }
        Set<String> expected = new LinkedHashSet<>(allIds);
        Set<String> actual = new LinkedHashSet<>(matched);
        if (!observations.keySet().equals(expected) || !actual.equals(expected)) {
            throw mismatch(
                    "properties.adapter-cache-identities",
                    null,
                    "Adapter Properties observations do not match Reports",
                    expected,
                    actual,
                    observations.keySet()
            );
        }
    }

    private static Map<String, Map<String, String>> loadBaseline(
            Path path,
            CorrectnessSpec spec,
            String proofId
    ) throws IOException {
        Map<String, Object> root = Jsons.parseObject(Files.readString(
                path,
                StandardCharsets.UTF_8
        ));
        if (!(root.get("schemaVersion") instanceof Number version)
                || version.intValue() != 1
                || !"initial".equals(root.get("phase"))
                || !"succeeded".equals(root.get("status"))
                || !proofId.equals(root.get("proofId"))
                || !spec.endpointManagerId().equals(
                root.get("endpointManagerId"))) {
            throw new IllegalArgumentException(
                    "Restart baseline is not successful initial evidence"
            );
        }
        Map<String, Object> encodedGroups = RuntimeApiClient.objectMap(
                root.get("groups"),
                "Restart baseline groups"
        );
        if (!encodedGroups.keySet().equals(spec.groupIds())) {
            throw new IllegalArgumentException(
                    "Restart baseline groups do not match fleet spec"
            );
        }
        Map<String, Map<String, String>> baseline = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group
                : spec.labWorkerKeysByGroup().entrySet()) {
            Map<String, Object> encodedGroup = RuntimeApiClient.objectMap(
                    encodedGroups.get(group.getKey()),
                    "Restart baseline group"
            );
            Map<String, Object> rawWorkers = RuntimeApiClient.objectMap(
                    encodedGroup.get("workerIdsByLabWorkerKey"),
                    "Restart baseline identities"
            );
            if (!rawWorkers.keySet().equals(
                    new LinkedHashSet<>(group.getValue()))) {
                throw new IllegalArgumentException(
                        "Restart baseline Lab Worker keys do not match fleet spec"
                );
            }
            Map<String, String> workers = new LinkedHashMap<>();
            for (String labWorkerKey : group.getValue()) {
                Object rawWorkerId = rawWorkers.get(labWorkerKey);
                if (!(rawWorkerId instanceof String workerId)) {
                    throw new IllegalArgumentException(
                            "Restart baseline Worker ID must be a string"
                    );
                }
                workers.put(labWorkerKey, workerId);
            }
            baseline.put(
                    group.getKey(),
                    Collections.unmodifiableMap(workers)
            );
        }
        return Collections.unmodifiableMap(baseline);
    }

    private static List<String> allWorkerIds(
            CorrectnessSpec spec,
            Map<String, Map<String, String>> inventory
    ) {
        List<String> workerIds = new ArrayList<>();
        spec.labWorkerKeysByGroup().keySet().forEach(groupId ->
                workerIds.addAll(inventory.get(groupId).values()));
        return List.copyOf(workerIds);
    }

    private static List<String> successfulIds(
            DirectCallOutcome call,
            List<String> expectedIds
    ) {
        return expectedIds.stream()
                .filter(workerId -> {
                    TargetOutcome outcome = call.results().get(workerId);
                    return outcome != null && outcome.successful();
                })
                .toList();
    }

    private static ProofFailure mismatch(
            String invariant,
            String groupId,
            String message,
            Set<String> expected,
            Set<String> actual
    ) {
        return mismatch(
                invariant,
                groupId,
                message,
                expected,
                actual,
                actual
        );
    }

    private static ProofFailure mismatch(
            String invariant,
            String groupId,
            String message,
            Set<String> expected,
            Set<String> successful,
            Set<String> returned
    ) {
        List<String> missing = expected.stream()
                .filter(value -> !successful.contains(value))
                .toList();
        List<String> unexpected = returned.stream()
                .filter(value -> !expected.contains(value))
                .toList();
        return new ProofFailure(
                invariant,
                groupId,
                message,
                missing,
                unexpected,
                List.of()
        );
    }

    private static void sleepForObservation() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while observing Worker Network state",
                    error
            );
        }
    }

    private static String safeMessage(RuntimeException error) {
        return error.getClass().getSimpleName();
    }

    private static final class ProofFailure extends IllegalStateException {

        private final String invariant;
        private final String groupId;
        private final List<String> missingIds;
        private final List<String> unexpectedIds;
        private final List<String> inconsistentIds;

        private ProofFailure(
                String invariant,
                String groupId,
                String message,
                List<String> missingIds,
                List<String> unexpectedIds,
                List<String> inconsistentIds
        ) {
            super(message);
            this.invariant = invariant;
            this.groupId = groupId;
            this.missingIds = List.copyOf(missingIds);
            this.unexpectedIds = List.copyOf(unexpectedIds);
            this.inconsistentIds = List.copyOf(inconsistentIds);
        }

        private ProofFailure withInconsistentIds(List<String> values) {
            return new ProofFailure(
                    invariant,
                    groupId,
                    getMessage(),
                    missingIds,
                    unexpectedIds,
                    values
            );
        }
    }

    private record CapabilityGroup(
            String workerGroupId,
            String payloadName,
            List<String> eventCodes
    ) {
        CapabilityGroup {
            eventCodes = List.copyOf(eventCodes);
        }
    }
}
