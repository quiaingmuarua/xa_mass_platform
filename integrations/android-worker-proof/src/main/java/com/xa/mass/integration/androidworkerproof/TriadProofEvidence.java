package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TriadProofEvidence {

    private final AndroidWorkerProofOptions options;
    private final String scenario;
    private final String phase;
    private final Map<String, Object> checks = new LinkedHashMap<>();
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private Map<String, String> workersByApplicationId = Map.of();

    TriadProofEvidence(
            AndroidWorkerProofOptions options,
            String scenario,
            String phase
    ) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.scenario = requireText(scenario, "scenario");
        this.phase = requireText(phase, "phase");
    }

    void workers(Map<String, String> value) {
        workersByApplicationId = validateWorkers(value);
    }

    void check(String name, Object value) {
        if (checks.putIfAbsent(requireText(name, "check name"), value) != null) {
            throw new IllegalArgumentException("Duplicate evidence check: " + name);
        }
    }

    void failure(ProofFailure failure) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("invariant", failure.invariant());
        encoded.put("message", failure.safeMessage());
        encoded.put("missingIds", failure.missingIds());
        encoded.put("unexpectedIds", failure.unexpectedIds());
        encoded.put("inconsistentIds", failure.inconsistentIds());
        failures.add(Collections.unmodifiableMap(encoded));
    }

    void unexpectedFailure(Throwable failure) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("invariant", "android-worker-triad-proof.unexpected");
        encoded.put("message", failure.getClass().getName());
        encoded.put("missingIds", List.of());
        encoded.put("unexpectedIds", List.of());
        encoded.put("inconsistentIds", List.of());
        failures.add(Collections.unmodifiableMap(encoded));
    }

    void write() throws IOException {
        Path path = options.evidenceFile();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                Jsons.toJson(toMap()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    Map<String, Object> toMap() {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("schemaVersion", 1);
        encoded.put("proofId", options.proofId());
        encoded.put("scenario", scenario);
        encoded.put("phase", phase);
        encoded.put("status", failures.isEmpty() ? "succeeded" : "failed");
        encoded.put("androidApiLevel", options.androidApiLevel());
        encoded.put("workerGroupId", AndroidWorkerProofConstants.WORKER_GROUP_ID);
        encoded.put("endpointManagerId", options.endpointManagerId());
        encoded.put("workers", encodedWorkers(workersByApplicationId));
        encoded.put("checks", new LinkedHashMap<>(checks));
        encoded.put("failures", List.copyOf(failures));
        return encoded;
    }

    static Baseline readBaseline(
            Path path,
            AndroidWorkerProofOptions options,
            String scenario,
            String expectedPhase
    ) {
        Map<String, Object> encoded;
        try {
            encoded = Jsons.parseObject(Files.readString(
                    path,
                    StandardCharsets.UTF_8
            ));
        } catch (IOException | IllegalArgumentException error) {
            throw new ProofFailure(
                    "triad.baseline.read",
                    "Android Worker triad baseline could not be read",
                    error
            );
        }
        if (!(encoded.get("schemaVersion") instanceof Number schemaVersion)
                || schemaVersion.intValue() != 1
                || !options.proofId().equals(encoded.get("proofId"))
                || !scenario.equals(encoded.get("scenario"))
                || !expectedPhase.equals(encoded.get("phase"))
                || !"succeeded".equals(encoded.get("status"))
                || !AndroidWorkerProofConstants.WORKER_GROUP_ID.equals(
                        encoded.get("workerGroupId")
                )
                || !options.endpointManagerId().equals(
                        encoded.get("endpointManagerId")
                )) {
            throw new ProofFailure(
                    "triad.baseline.contract",
                    "Android Worker triad baseline is incompatible"
            );
        }
        Map<String, String> workers = new LinkedHashMap<>();
        for (Object rawWorker : JsonValues.array(
                encoded.get("workers"),
                "triad baseline workers"
        )) {
            Map<String, Object> worker = JsonValues.object(
                    rawWorker,
                    "triad baseline worker"
            );
            String applicationId = JsonValues.requiredString(
                    worker,
                    "applicationId"
            );
            AndroidWorkerTriadTopology.WorkerAddress address =
                    AndroidWorkerTriadTopology.WORKERS.stream()
                            .filter(candidate -> candidate.applicationId().equals(
                                    applicationId
                            ))
                            .findFirst()
                            .orElseThrow(() -> new ProofFailure(
                                    "triad.baseline.topology",
                                    "Triad baseline contains an unknown application"
                            ));
            if (!address.deviceBaseUrl().toString().equals(
                    JsonValues.requiredString(worker, "deviceHostUrl")
            ) || workers.putIfAbsent(
                    applicationId,
                    JsonValues.requiredString(worker, "workerId")
            ) != null) {
                throw new ProofFailure(
                        "triad.baseline.topology",
                        "Triad baseline topology is inconsistent"
                );
            }
        }
        return new Baseline(validateWorkers(workers));
    }

    private static List<Map<String, Object>> encodedWorkers(
            Map<String, String> workers
    ) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (AndroidWorkerTriadTopology.WorkerAddress address
                : AndroidWorkerTriadTopology.WORKERS) {
            Map<String, Object> worker = new LinkedHashMap<>();
            worker.put("applicationId", address.applicationId());
            worker.put("deviceHostUrl", address.deviceBaseUrl().toString());
            worker.put("workerId", workers.get(address.applicationId()));
            encoded.add(Collections.unmodifiableMap(worker));
        }
        return List.copyOf(encoded);
    }

    private static Map<String, String> validateWorkers(
            Map<String, String> value
    ) {
        Map<String, String> copied = new LinkedHashMap<>(
                java.util.Objects.requireNonNull(value, "workers")
        );
        if (!copied.keySet().equals(AndroidWorkerTriadTopology.applicationIds())) {
            throw new IllegalArgumentException(
                    "workers must contain the fixed Android triad applications"
            );
        }
        Set<String> uniqueWorkerIds = new LinkedHashSet<>();
        for (String workerId : copied.values()) {
            if (workerId == null || workerId.isBlank()
                    || !uniqueWorkerIds.add(workerId)) {
                throw new IllegalArgumentException(
                        "workers must contain three distinct worker IDs"
                );
            }
        }
        return Collections.unmodifiableMap(copied);
    }

    record Baseline(Map<String, String> workersByApplicationId) {
        Baseline {
            workersByApplicationId = validateWorkers(workersByApplicationId);
        }

        String workerId(AndroidWorkerTriadTopology.WorkerAddress address) {
            return workersByApplicationId.get(address.applicationId());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
