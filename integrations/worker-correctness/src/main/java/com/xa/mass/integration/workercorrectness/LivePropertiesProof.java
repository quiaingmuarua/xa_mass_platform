package com.xa.mass.integration.workercorrectness;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Finite HTTP proof: all mutations enter the Lab and all remote observations use Runtime APIs. */
final class LivePropertiesProof {
    static final long CHECKPOINT_MILLIS = 5_000;
    static final long PHASE_MILLIS = 120_000;
    private static final String STRING_GROUP = "scenario-string-utils-workers";
    private static final String PHONE_GROUP = "scenario-phone-number-workers";
    private final CorrectnessOptions options;
    private final CorrectnessSpec spec;
    private final LivePropertiesEvidence evidence = new LivePropertiesEvidence();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
    private final List<Worker> workers = new ArrayList<>();
    private final long started = System.nanoTime();
    private final long phaseDeadline = started + TimeUnit.MILLISECONDS.toNanos(PHASE_MILLIS);

    private LivePropertiesProof(CorrectnessOptions options, CorrectnessSpec spec) {
        this.options = options;
        this.spec = spec;
    }

    static void execute(CorrectnessOptions options) throws IOException {
        LivePropertiesProof proof = new LivePropertiesProof(options, CorrectnessSpec.load(options.correctnessSpec()));
        try {
            proof.run();
            proof.evidence.stage = "complete";
        } catch (Exception error) {
            // Untrusted response/file contents and their exception messages never enter artifacts/logs.
            proof.evidence.failure = error instanceof InvariantFailure ? error.getMessage()
                    : "unexpected-" + error.getClass().getSimpleName();
        } finally {
            proof.http.close();
            proof.evidence.elapsedMillis = elapsedMillis(proof.started);
            proof.evidence.write(options.evidenceFile(), options.proofId(), proof.spec.endpointManagerId());
        }
        if (proof.evidence.failure != null) {
            throw new IllegalStateException("Live Properties failed at " + proof.evidence.stage
                    + ": " + proof.evidence.failure);
        }
    }

    private void run() throws Exception {
        require(spec.labWorkerKeysByGroup().size() == 2
                && spec.labWorkerKeysByGroup().get(STRING_GROUP).size() == 50
                && spec.labWorkerKeysByGroup().get(PHONE_GROUP).size() == 50, "fixed-world-required");
        Map<String, Object> baseline = Jsons.parseObject(Files.readString(
                options.baselineFile(CorrectnessOptions.Phase.LIVE_PROPERTIES)));
        require("succeeded".equals(baseline.get("status"))
                && "initial".equals(baseline.get("phase"))
                && options.proofId().equals(baseline.get("proofId"))
                && spec.endpointManagerId().equals(baseline.get("endpointManagerId")), "initial-evidence-required");
        addWorker("target", STRING_GROUP, 0, baseline);
        addWorker("same-file-control", STRING_GROUP, 1, baseline);
        addWorker("cross-group-control", PHONE_GROUP, 0, baseline);
        Worker target = workers.getFirst();
        require(target.baseline().get("labInventoryKey").equals(workers.get(1).baseline().get("labInventoryKey")),
                "same-file-control-required");
        Map<String, String> current = target.baseline();
        checkpoint("preflight", System.nanoTime(), List.of(current));
        Map<String, String> coordinates = Map.of(
                "labInventoryKey", current.get("labInventoryKey"),
                "labInventoryLine", current.get("labInventoryLine"));
        for (int round = 1; round <= 8; round++) {
            evidence.stage = "replacement-" + round;
            Map<String, String> full = new LinkedHashMap<>(coordinates);
            full.putAll(Map.of("round", Integer.toString(round), "sequence", "0", "mirror", "0",
                    "preserved", "baseline", "empty", ""));
            long sent = mutate("PUT", full);
            checkpoint(evidence.stage, sent, List.of(current, Map.copyOf(full)));
            current = Map.copyOf(full);
            evidence.stage = "incremental-burst-" + round;
            List<Map<String, String>> committed = new ArrayList<>();
            committed.add(current);
            for (int sequence = 1; sequence <= 32; sequence++) {
                String value = round + ":" + sequence;
                Map<String, String> delta = Map.of("sequence", value, "mirror", value,
                        "delta-" + round + "-" + sequence, value);
                sent = mutate("PATCH", delta);
                Map<String, String> next = new LinkedHashMap<>(current);
                next.putAll(delta);
                current = Map.copyOf(next);
                committed.add(current);
            }
            // No observation/ACK waits between the 32 mutation requests.
            checkpoint(evidence.stage, sent, committed);
        }
        evidence.stage = "coordinates-only-replacement";
        long sent = mutate("PUT", coordinates);
        checkpoint(evidence.stage, sent, List.of(current, coordinates));
        require(System.nanoTime() <= phaseDeadline, "phase-deadline");
        require(evidence.mutationRequests == 265 && evidence.sendAcceptedCount == 265
                && evidence.replacementCount == 9 && evidence.incrementalCount == 256, "mutation-counts");
    }

    private void addWorker(String role, String group, int index, Map<String, Object> initial) throws Exception {
        String key = spec.labWorkerKeysByGroup().get(group).get(index);
        Map<String, Object> groupEvidence = object(object(initial.get("groups")).get(group));
        String id = text(object(groupEvidence.get("workerIdsByLabWorkerKey")).get(key));
        String path = "/lab/v1/workers/" + encode(group) + "/" + encode(key);
        Map<String, Object> lab = observe(() -> request(options.labBaseUrl(), "GET", path, null,
                phaseDeadline, true), phaseDeadline);
        requireLabRun(lab, group, key, id);
        Map<String, String> properties = stringMap(lab.get("workerProperties"));
        require(key.equals(properties.get("labInventoryKey") + ":" + properties.get("labInventoryLine")),
                "lab-file-coordinate");
        Worker worker = new Worker(group, key, id, path, properties);
        workers.add(worker);
        evidence.workers.add(Map.of("role", role, "workerGroupId", group, "labWorkerKey", key,
                "workerId", id, "baselineSha256", digest(properties)));
    }

    private long mutate(String method, Map<String, String> properties) throws Exception {
        long sent = System.nanoTime();
        evidence.mutationRequests++;
        Map<String, Object> response = request(options.labBaseUrl(), method,
                workers.getFirst().path() + ":properties", properties, phaseDeadline, false);
        require(response.keySet().equals(java.util.Set.of("persisted", "sendAccepted")), "mutation-response-shape");
        if (Boolean.TRUE.equals(response.get("persisted"))) {
            evidence.persistedCount++;
        }
        if (Boolean.TRUE.equals(response.get("sendAccepted"))) {
            evidence.sendAcceptedCount++;
        }
        require(Boolean.TRUE.equals(response.get("persisted"))
                && Boolean.TRUE.equals(response.get("sendAccepted")), "mutation-not-accepted");
        if ("PUT".equals(method)) {
            evidence.replacementCount++;
        } else {
            evidence.incrementalCount++;
        }
        return sent;
    }

    private void checkpoint(String name, long sent, List<Map<String, String>> committed) throws Exception {
        long deadline = Math.min(phaseDeadline, sent + TimeUnit.MILLISECONDS.toNanos(CHECKPOINT_MILLIS));
        Map<String, String> expected = committed.getLast();
        long adapterMillis = -1;
        long runtimeMillis = -1;
        int observations = 0;
        while (System.nanoTime() < deadline && (adapterMillis < 0 || runtimeMillis < 0)) {
            try {
                Map<String, String> cached = adapterSnapshot(deadline);
                requireCommitted(cached, committed);
                observations++;
                if (expected.equals(cached) && adapterMillis < 0) {
                    adapterMillis = observedWithin(sent, System.nanoTime());
                }
            } catch (TemporaryReadFailure unavailable) {
                evidence.temporaryReadFailures++;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Map<String, String> runtime = runtimeSnapshot(deadline, committed);
                requireCommitted(runtime, committed);
                observations++;
                if (expected.equals(runtime) && runtimeMillis < 0) {
                    runtimeMillis = observedWithin(sent, System.nanoTime());
                }
            } catch (TemporaryReadFailure unavailable) {
                evidence.temporaryReadFailures++;
            }
            if (adapterMillis < 0 || runtimeMillis < 0) {
                pause(deadline);
            }
        }
        require(adapterMillis >= 0 && runtimeMillis >= 0, "checkpoint-observation-deadline");
        for (Worker worker : workers) {
            Map<String, Object> lab = observe(() -> request(options.labBaseUrl(), "GET", worker.path(),
                    null, phaseDeadline, true), phaseDeadline);
            requireLabRun(lab, worker.group(), worker.key(), worker.id());
            require((worker == workers.getFirst() ? expected : worker.baseline())
                    .equals(stringMap(lab.get("workerProperties"))), "lab-file-properties-mismatch");
        }
        Map<String, Object> states = object(observe(() -> request(options.serverBaseUrl(), "POST",
                "/api/v1/runtime-view/endpoint-managers/" + spec.endpointManagerId() + "/workers:network-observe",
                workers.stream().map(Worker::id).toList(), phaseDeadline, true), phaseDeadline).get("statesByWorkerId"));
        for (Worker worker : workers) {
            require("connected".equals(states.get(worker.id())), "worker-connection-changed");
        }
        evidence.checkpoint(name, expected.size(), digest(expected), adapterMillis, runtimeMillis, observations);
    }

    private Map<String, String> adapterSnapshot(long deadline) throws Exception {
        Map<String, Object> response = request(options.serverBaseUrl(), "POST",
                "/api/v1/worker-delivery/endpoint-managers/" + spec.endpointManagerId() + "/direct-calls",
                Map.of("messageType", "platform.adapter.worker-properties.snapshot",
                        "opaquePayload", Jsons.toJson(Map.of("workerIds", workers.stream().map(Worker::id).toList())),
                        "waitTimeoutMillis", 1_000), deadline, true);
        Map<String, Object> results = object(response.get("results"));
        require(results.size() == 1, "adapter-result-identities");
        Map<String, Object> result = object(results.get(spec.endpointManagerId()));
        if ("unobserved".equals(result.get("status")) && "timeout".equals(result.get("reason"))) {
            throw new TemporaryReadFailure();
        }
        require("observed".equals(result.get("status")) && "200".equals(result.get("outcomeCode")),
                "adapter-snapshot-rejected");
        Map<String, Object> snapshots = object(Jsons.parseObject(text(result.get("opaqueResultPayload")))
                .get("propertiesByWorkerId"));
        require(snapshots.size() == workers.size(), "adapter-snapshot-identities");
        Map<String, String> target = null;
        for (Worker worker : workers) {
            Map<String, Object> snapshot = object(snapshots.get(worker.id()));
            require(snapshot.get("updatedAtMillis") instanceof Number time && time.longValue() > 0,
                    "adapter-snapshot-observation-metadata");
            Map<String, String> properties = stringMap(snapshot.get("properties"));
            if (worker == workers.getFirst()) {
                target = properties;
            } else {
                require(worker.baseline().equals(properties), "control-adapter-properties-changed");
            }
        }
        return target;
    }

    private Map<String, String> runtimeSnapshot(long deadline, List<Map<String, String>> committed) throws Exception {
        Map<String, String> target = null;
        for (String group : List.of(STRING_GROUP, PHONE_GROUP)) {
            Map<String, Object> response = request(options.serverBaseUrl(), "POST",
                    "/api/v1/runtime-view/worker-groups/" + group + "/workers:preview", 100, deadline, true);
            require(response.get("unreadableCount") instanceof Number count && count.intValue() == 0,
                    "runtime-unreadable-workers");
            require(response.get("workers") instanceof List<?>, "runtime-workers-shape");
            List<?> descriptors = (List<?>) response.get("workers");
            require(descriptors.size() == 50, "runtime-worker-count");
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Object descriptor : descriptors) {
                Map<String, Object> view = object(descriptor);
                require(group.equals(view.get("workerGroupId"))
                        && byId.put(text(view.get("workerId")), view) == null, "runtime-worker-identities");
            }
            for (Worker worker : workers) {
                if (!worker.group().equals(group)) {
                    continue;
                }
                Map<String, Object> view = object(byId.get(worker.id()));
                require(spec.endpointManagerId().equals(view.get("endpointManagerId")), "runtime-worker-binding");
                Map<String, String> properties = stringMap(view.get("workerProperties"));
                if (worker == workers.getFirst()) {
                    requireCommitted(properties, committed);
                    target = properties;
                } else {
                    require(worker.baseline().equals(properties), "control-runtime-properties-changed");
                }
            }
        }
        return target;
    }

    private Map<String, Object> request(URI base, String method, String path, Object body,
                                        long deadline, boolean observation) throws Exception {
        long remaining = Math.min(deadline, phaseDeadline) - System.nanoTime();
        require(remaining > 0, "request-deadline");
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofNanos(Math.min(TimeUnit.SECONDS.toNanos(2), remaining)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Jsons.toJson(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            if (observation) {
                throw new TemporaryReadFailure();
            }
            throw new InvariantFailure("mutation-result-unknown");
        }
        if (observation && (response.statusCode() == 429 || response.statusCode() == 502
                || response.statusCode() == 503 || response.statusCode() == 504)) {
            throw new TemporaryReadFailure();
        }
        require(response.statusCode() == 200, observation ? "observation-http-rejected" : "mutation-http-rejected");
        return Jsons.parseObject(response.body());
    }

    private Map<String, Object> observe(Read read, long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            try {
                return read.get();
            } catch (TemporaryReadFailure unavailable) {
                evidence.temporaryReadFailures++;
                pause(deadline);
            }
        }
        throw new InvariantFailure("observation-read-deadline");
    }

    static void requireCommitted(Map<String, String> actual, List<Map<String, String>> committed) {
        require(committed.contains(actual), "uncommitted-properties-snapshot");
    }

    static long observedWithin(long sent, long observed) {
        require(observed >= sent && observed - sent <= TimeUnit.MILLISECONDS.toNanos(CHECKPOINT_MILLIS),
                "checkpoint-observation-deadline");
        return TimeUnit.NANOSECONDS.toMillis(observed - sent);
    }

    static String digest(Map<String, String> properties) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    Jsons.toJson(new TreeMap<>(properties)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required");
        }
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        object(raw).forEach((key, value) -> {
            require(value instanceof String, "properties-must-be-strings");
            result.put(key, (String) value);
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> object(Object raw) {
        return RuntimeApiClient.objectMap(raw, "Live Properties response");
    }

    private static String text(Object raw) {
        require(raw instanceof String value && !value.isBlank(), "response-string-required");
        return (String) raw;
    }

    static void requireLabRun(Map<String, Object> lab, String group, String key, String id) {
        require(id.equals(lab.get("workerId")) && group.equals(lab.get("workerGroupId"))
                && key.equals(lab.get("labWorkerKey")), "worker-run-identity-changed");
        require("RUNNING".equals(lab.get("desiredState")) && "RUNNING".equals(lab.get("runtimeState")),
                "worker-not-running");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long elapsedMillis(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }

    private static void pause(long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
        }
    }

    private static void require(boolean condition, String invariant) {
        if (!condition) {
            throw new InvariantFailure(invariant);
        }
    }

    private record Worker(String group, String key, String id, String path,
                          Map<String, String> baseline) { }

    @FunctionalInterface
    private interface Read { Map<String, Object> get() throws Exception; }

    private static final class TemporaryReadFailure extends RuntimeException { }
    private static final class InvariantFailure extends IllegalStateException {
        private InvariantFailure(String invariant) { super(invariant); }
    }
}
