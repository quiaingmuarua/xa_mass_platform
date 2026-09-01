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
import java.util.List;
import java.util.Map;

final class ProofEvidence {

    private final AndroidWorkerProofOptions options;
    private final String scenario;
    private final String phase;
    private final Map<String, Object> checks = new LinkedHashMap<>();
    private final List<Map<String, Object>> failures = new ArrayList<>();
    private String workerId;
    private Boolean baselineIdentityMatched;

    ProofEvidence(
            AndroidWorkerProofOptions options,
            String scenario,
            String phase
    ) {
        this.options = options;
        this.scenario = requireText(scenario, "scenario");
        this.phase = requireText(phase, "phase");
    }

    void workerId(String value) {
        workerId = requireText(value, "workerId");
    }

    void baselineIdentityMatched(boolean value) {
        baselineIdentityMatched = value;
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
        encoded.put("invariant", "android-worker-proof.unexpected");
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
        encoded.put("applicationId", AndroidWorkerProofConstants.APPLICATION_ID);
        encoded.put("workerGroupId", AndroidWorkerProofConstants.WORKER_GROUP_ID);
        encoded.put("endpointManagerId", options.endpointManagerId());
        encoded.put("workerId", workerId);
        encoded.put("baselineIdentityMatched", baselineIdentityMatched);
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
                    "baseline.read",
                    "Android Worker proof baseline could not be read",
                    error
            );
        }
        if (!(encoded.get("schemaVersion") instanceof Number schemaVersion)
                || schemaVersion.intValue() != 1
                || !options.proofId().equals(encoded.get("proofId"))
                || !scenario.equals(encoded.get("scenario"))
                || !expectedPhase.equals(encoded.get("phase"))
                || !"succeeded".equals(encoded.get("status"))
                || !AndroidWorkerProofConstants.APPLICATION_ID.equals(
                        encoded.get("applicationId")
                )
                || !AndroidWorkerProofConstants.WORKER_GROUP_ID.equals(
                        encoded.get("workerGroupId")
                )
                || !options.endpointManagerId().equals(
                        encoded.get("endpointManagerId")
                )) {
            throw new ProofFailure(
                    "baseline.contract",
                    "Android Worker proof baseline is incompatible"
            );
        }
        String workerId = JsonValues.requiredString(encoded, "workerId");
        Map<String, Object> checks = JsonValues.object(
                encoded.get("checks"),
                "baseline checks"
        );
        return new Baseline(
                workerId,
                Collections.unmodifiableMap(new LinkedHashMap<>(checks))
        );
    }

    record Baseline(String workerId, Map<String, Object> checks) {
        String requiredCheck(String name) {
            return JsonValues.requiredString(checks, name);
        }

        boolean requiredBooleanCheck(String name) {
            Object value = checks.get(name);
            if (!(value instanceof Boolean result)) {
                throw new ProofFailure(
                        "baseline.check",
                        "Android Worker proof baseline check is invalid"
                );
            }
            return result;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
