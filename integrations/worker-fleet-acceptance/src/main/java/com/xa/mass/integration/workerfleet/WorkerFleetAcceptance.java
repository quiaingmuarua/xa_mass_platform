package com.xa.mass.integration.workerfleet;

import com.xa.mass.integration.workerfleet.FleetCommandLineOptions.Phase;
import com.xa.mass.integration.workerfleet.RuntimeApiClient.DirectCallOutcome;
import com.xa.mass.integration.workerfleet.RuntimeApiClient.TargetOutcome;
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

final class WorkerFleetAcceptance {

    private static final String WORKER_PROBE_EVENT =
            "platform.worker.probe";
    private static final String WORKER_PROPERTIES_EVENT =
            "platform.worker.properties.snapshot";
    private static final String ADAPTER_PROPERTIES_EVENT =
            "platform.adapter.worker-properties.snapshot";

    private WorkerFleetAcceptance() {
    }

    static void execute(FleetCommandLineOptions options) throws IOException {
        Phase phase = options.requiredPhase();
        FleetSpec spec = FleetSpec.load(options.fleetSpec());
        FleetEvidence evidence = new FleetEvidence(
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
                    "worker-fleet.acceptance",
                    null,
                    safeMessage(error),
                    List.of(),
                    List.of(),
                    List.of()
            );
            proofFailure = error;
        } catch (IOException error) {
            evidence.failure(
                    "worker-fleet.acceptance-io",
                    null,
                    "Worker Fleet proof could not read required evidence",
                    List.of(),
                    List.of(),
                    List.of()
            );
            proofFailure = new IllegalStateException(
                    "Worker Fleet proof could not read required evidence",
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
            FleetCommandLineOptions options,
            Phase phase,
            FleetSpec spec,
            FleetEvidence evidence
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
    }

    private static void verifyBaseline(
            Map<String, Map<String, String>> inventory,
            Map<String, Map<String, String>> baseline,
            FleetEvidence evidence
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
            FleetSpec spec,
            Map<String, Map<String, String>> inventory,
            FleetEvidence evidence,
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
            FleetSpec spec,
            Map<String, Map<String, String>> inventory,
            Map<String, String> states,
            FleetEvidence evidence
    ) {
        for (String groupId : spec.clientWorkerKeysByGroup().keySet()) {
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
            FleetSpec spec,
            Map<String, Map<String, String>> inventory,
            FleetEvidence evidence
    ) {
        for (String groupId : spec.clientWorkerKeysByGroup().keySet()) {
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
            FleetSpec spec,
            Map<String, Map<String, String>> inventory,
            FleetEvidence evidence
    ) {
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (String groupId : spec.clientWorkerKeysByGroup().keySet()) {
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
        for (String groupId : spec.clientWorkerKeysByGroup().keySet()) {
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
            FleetSpec spec,
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
                : spec.clientWorkerKeysByGroup().entrySet()) {
            Map<String, Object> encodedGroup = RuntimeApiClient.objectMap(
                    encodedGroups.get(group.getKey()),
                    "Restart baseline group"
            );
            Map<String, Object> rawWorkers = RuntimeApiClient.objectMap(
                    encodedGroup.get("workerIdsByClientWorkerKey"),
                    "Restart baseline identities"
            );
            if (!rawWorkers.keySet().equals(
                    new LinkedHashSet<>(group.getValue()))) {
                throw new IllegalArgumentException(
                        "Restart baseline client keys do not match fleet spec"
                );
            }
            Map<String, String> workers = new LinkedHashMap<>();
            for (String clientWorkerKey : group.getValue()) {
                Object rawWorkerId = rawWorkers.get(clientWorkerKey);
                if (!(rawWorkerId instanceof String workerId)) {
                    throw new IllegalArgumentException(
                            "Restart baseline Worker ID must be a string"
                    );
                }
                workers.put(clientWorkerKey, workerId);
            }
            baseline.put(
                    group.getKey(),
                    Collections.unmodifiableMap(workers)
            );
        }
        return Collections.unmodifiableMap(baseline);
    }

    private static List<String> allWorkerIds(
            FleetSpec spec,
            Map<String, Map<String, String>> inventory
    ) {
        List<String> workerIds = new ArrayList<>();
        spec.clientWorkerKeysByGroup().keySet().forEach(groupId ->
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
}
