package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local device admission witness; Python owns the intervening process restart. */
public final class AndroidWorkerCooldownMain {
    private static final String SCENARIO = "connection-cooldown";
    private static final String GROUP = AndroidWorkerProofConstants.WORKER_GROUP_ID;
    private static final String EVENT = "extension.worker.android.string.digest";
    private static final List<String> SEEDS = List.of("a", "abc", "中文");
    private static final List<String> DIGESTS = List.of("0cc175b9c0f1b6a831c399e269772661",
            "900150983cd24fb0d6963f7d28e17f72", "a7bac2239fcdcb3a067903d8077c4a07");
    private static final List<String> IDS = List.of("cooldown-one", "cooldown-two", "cooldown-three");
    private static final Map<String, Object> SELECTOR = Map.of("executorName", "worker.any", "input", Map.of());
    private final AndroidWorkerProofOptions options;
    private final ProofEvidence evidence;
    private final JsonHttpClient http;
    private final AndroidDeviceHostClient device;
    private final AndroidRuntimeApiClient runtime;

    private AndroidWorkerCooldownMain(AndroidWorkerProofOptions options) {
        this.options = options;
        evidence = new ProofEvidence(options, SCENARIO, options.phase());
        http = new JsonHttpClient(options.serverBaseUrl(), options, Map.of("Authorization", "Bearer xak_dev_1000"));
        device = new AndroidDeviceHostClient(new JsonHttpClient(options.deviceBaseUrl(), options, Map.of()));
        runtime = new AndroidRuntimeApiClient(http);
    }

    public static void main(String[] arguments) throws IOException {
        var proof = new AndroidWorkerCooldownMain(AndroidWorkerProofOptions.parse(arguments));
        try { proof.run(); }
        catch (ProofFailure failure) { proof.evidence.failure(failure); throw failure; }
        catch (RuntimeException | Error failure) { proof.evidence.unexpectedFailure(failure); throw failure; }
        finally { proof.evidence.write(); }
    }

    private void run() {
        AndroidWorkerProofAssertions.awaitDeviceHealth(device, options.maximumWait());
        AndroidWorkerProofAssertions.requireDeviceEvents(device);
        switch (options.phase()) {
            case "initial" -> coolOnlineWorker();
            case "process-restart" -> releaseAfterRestart();
            default -> throw new IllegalArgumentException("Cooldown phase must be initial or process-restart");
        }
    }

    private void coolOnlineWorker() {
        options.baselineFile(false);
        var before = AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), null);
        String workerId = before.workerId();
        evidence.workerId(workerId);
        AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), workerId, options.maximumWait());
        var current = request("GET", control(workerId), null, 200);
        require(Boolean.TRUE.equals(current.get("supported")) && "allowed".equals(current.get("status")), "initial-admission");
        long until = JsonValues.requiredLong(current, "readAt") + Duration.ofDays(1).toMillis();
        var change = new LinkedHashMap<String, Object>();
        change.put("requestId", UUID.randomUUID().toString());
        change.put("expectedRevision", current.get("revision"));
        change.put("cooldownUntilMillis", until);
        change.put("reason", "Android local cooldown proof");
        var saved = request("PUT", control(workerId), change, 200);
        require("cooldown".equals(saved.get("status")), "cooldown-saved");
        evidence.check("revision", JsonValues.requiredString(saved, "revision"));
        evidence.check("cooldownUntilMillis", until);
        evidence.check("processedBeforeRestart", before.processedCommands());
        awaitCooled(workerId, until);
        awaitNoRoute(workerId);
        evidence.check("onlineWorkerCooled", true);
    }

    private void releaseAfterRestart() {
        var baseline = ProofEvidence.readBaseline(options.baselineFile(true), options, SCENARIO, "initial");
        String workerId = baseline.workerId();
        evidence.workerId(workerId);
        long until = JsonValues.requiredLong(baseline.checks(), "cooldownUntilMillis");
        var stopped = awaitCooled(workerId, until);
        require(stopped.processedCommands() == 0, "new-process-counters");
        evidence.baselineIdentityMatched(true);
        awaitNoRoute(workerId);
        var saved = request("GET", control(workerId), null, 200);
        require("cooldown".equals(saved.get("status"))
                && baseline.requiredCheck("revision").equals(saved.get("revision")), "restart-policy-retained");
        var release = new LinkedHashMap<String, Object>();
        release.put("requestId", UUID.randomUUID().toString());
        release.put("expectedRevision", saved.get("revision"));
        release.put("cooldownUntilMillis", null);
        release.put("reason", "Early release after real App restart");
        var allowed = request("PUT", control(workerId), release, 200);
        require("allowed".equals(allowed.get("status"))
                && !saved.get("revision").equals(allowed.get("revision")), "early-release");
        // Recovery is explicit here. Hour-scale timer semantics belong to the App clock tests.
        device.start();
        AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), workerId);
        AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), workerId, options.maximumWait());
        runtime.requirePropertiesRelation(options.endpointManagerId(), workerId);
        verifyFiniteTask(workerId);
        evidence.check("processRestartRejected", true);
        evidence.check("earlyReleaseRecovered", true);
    }

    private AndroidDeviceHostClient.Snapshot awaitCooled(String workerId, long until) {
        return ProofWait.until(options.maximumWait(), device::snapshot, value -> {
            if (value.workerId() != null) require(workerId.equals(value.workerId()), "identity-stable");
            return "STOPPED".equals(value.state()) && "COOLDOWN".equals(value.stopReason())
                    && workerId.equals(value.workerId()) && Long.valueOf(until).equals(value.cooldownUntilMillis())
                    && value.nextConnectionCheckAtMillis() != null;
        }, "cooldown.device-stopped", "Android did not observe connection cooldown", workerId);
    }

    private void awaitNoRoute(String workerId) {
        ProofWait.until(options.maximumWait(), () -> {
            var target = runtime.callAdapter(options.endpointManagerId(), "platform.adapter.worker-connections.snapshot",
                    Jsons.toJson(Map.of("workerIds", List.of(workerId))));
            target.requireSuccessful("Cooldown route observation");
            var states = object(Jsons.parseObject(target.opaqueResultPayload()).get("stateByWorkerId"));
            require(states.keySet().equals(Set.of(workerId)), "route-identity-set");
            return states.get(workerId);
        }, state -> "UNKNOWN".equals(state) || "DISCONNECTED".equals(state),
                "cooldown.route-gone", "Cooled Worker retained a connected route", workerId);
    }

    private void verifyFiniteTask(String workerId) {
        long processedBefore = device.snapshot().processedCommands();
        String taskId = JsonValues.requiredString(request("POST", "/api/v1/tasks", Map.of(
                "requestId", options.proofId() + ":recovered", "projectId", "default", "workerGroupId", GROUP,
                "refill", List.of(Map.of("poolName", "any", "target", Map.of(), "count", 100))), 200), "taskId");
        evidence.check("taskId", taskId);
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < IDS.size(); i++) items.add(Map.of("messageId", IDS.get(i), "eventCode", EVENT,
                "payload", Map.of("algorithm", "MD5", "value", SEEDS.get(i)), "workerSelector", SELECTOR));
        var appended = request("POST", task(taskId) + "/items", items, 200);
        require(appended.keySet().equals(Set.copyOf(IDS)), "import-identity-set");
        request("POST", task(taskId) + "/approve", Map.of("expectedItemCount", 3), 200);
        var detail = ProofWait.until(options.maximumWait(),
                () -> request("GET", "/api/v1/projects/default/tasks/" + taskId, null, 200),
                value -> "terminal".equals(value.get("scoreBand")),
                "cooldown.finite-terminal", "Recovered Android finite Task did not finish", taskId);
        var descriptor = object(detail.get("task"));
        require(GROUP.equals(descriptor.get("workerGroupId")) && "CLOSE_WHEN_IDLE".equals(descriptor.get("idleDisposition")), "finite-group");
        var refill = JsonValues.array(descriptor.get("refill"), "refill");
        require(refill.size() == 1 && "any".equals(object(refill.getFirst()).get("poolName")), "saved-any-refill");
        var results = ProofWait.until(options.maximumWait(),
                () -> request("POST", task(taskId) + "/results:load", IDS, 200),
                value -> value.keySet().equals(Set.copyOf(IDS)) && value.values().stream().allMatch(row -> "succeeded".equals(object(row).get("status"))),
                "cooldown.finite-results", "Recovered Android Results did not converge", taskId);
        var states = request("POST", task(taskId) + "/items:states", IDS, 200);
        require(states.keySet().equals(Set.copyOf(IDS)), "terminal-identity-set");
        for (int i = 0; i < IDS.size(); i++) {
            requireResult(i, object(results.get(IDS.get(i))).get("opaqueResultPayload"));
            var state = object(states.get(IDS.get(i)));
            require("terminal".equals(state.get("band")) && "succeeded".equals(state.get("outcomeName")), "item-terminal");
        }
        var preview = request("GET", task(taskId) + "/results:preview", null, 200);
        require(Boolean.FALSE.equals(preview.get("truncated")), "preview-not-truncated");
        var seen = new HashSet<String>();
        for (Object raw : JsonValues.array(preview.get("results"), "results")) {
            var row = object(raw);
            require(seen.add(JsonValues.requiredString(row, "messageId")), "preview-unique");
            var item = object(row.get("item"));
            require(EVENT.equals(item.get("eventCode")) && SELECTOR.equals(object(item.get("workerSelector"))), "saved-pool-query");
            int index = IDS.indexOf(row.get("messageId"));
            require(index >= 0 && Map.of("algorithm", "MD5", "value", SEEDS.get(index)).equals(object(item.get("payload"))), "saved-input");
        }
        require(seen.equals(Set.copyOf(IDS)), "preview-identities");
        var exported = http.sendText("POST", task(taskId) + "/results:export", null, "application/json", "cooldown.export");
        require(exported.statusCode() == 200, "export-http");
        verifyExport(exported.body());
        var observed = ProofWait.until(options.maximumWait(), device::snapshot,
                value -> value.processedCommands() >= processedBefore + 3,
                "cooldown.device-execution", "Recovered device did not execute all digest commands", workerId);
        require(workerId.equals(observed.workerId()) && "RUNNING".equals(observed.state()), "executing-worker");
        evidence.check("poolName", "any");
        evidence.check("query", "worker.any");
        evidence.check("rawResults", 3);
        evidence.check("jsonlRows", 3);
        evidence.check("deviceDigestExecutionsObserved", true);
    }

    static void verifyExport(String body) {
        var exported = new HashMap<String, Object>();
        for (String line : body.lines().toList()) {
            var row = Jsons.parseObject(line);
            String id = JsonValues.requiredString(row, "messageId");
            require(row.keySet().equals(Set.of("messageId", "opaqueResultPayload"))
                    && row.get("opaqueResultPayload") instanceof String, "export-shape");
            require(!exported.containsKey(id), "export-duplicate");
            exported.put(id, row.get("opaqueResultPayload"));
        }
        require(exported.keySet().equals(Set.copyOf(IDS)), "export-identity-set");
        for (int i = 0; i < IDS.size(); i++) requireResult(i, exported.get(IDS.get(i)));
    }

    private static void requireResult(int index, Object encoded) {
        require(encoded instanceof String, "result-content-type");
        require(Jsons.parseObject((String) encoded).equals(Map.of("algorithm", "MD5", "input", SEEDS.get(index), "digest", DIGESTS.get(index))), "fixed-digest");
    }
    private Map<String, Object> request(String method, String path, Object body, int status) {
        var response = http.send(method, path, body, "cooldown");
        require(response.statusCode() == status, "http-" + response.statusCode());
        return response.body();
    }
    private static Map<String, Object> object(Object value) { return JsonValues.object(value, "object"); }
    private static String task(String id) { return "/api/v1/tasks/" + id; }
    private static String control(String workerId) { return "/api/v1/worker-groups/" + GROUP + "/workers/" + workerId + "/connection-control"; }
    private static void require(boolean value, String check) {
        if (!value) throw new ProofFailure("cooldown." + check, "Android connection cooldown assertion failed: " + check);
    }
}
