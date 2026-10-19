package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Public finite-task business proof. No Scenario, Redis, Kernel, or SDK implementation imports. */
public final class AndroidWorkerBatchChecksMain {
    private static final String SCENARIO = "batch-checks";
    private static final String CHECK = "android-md5-prefix";
    private static final String EVENT = "extension.worker.android.string.digest";
    private static final String GROUP = AndroidWorkerProofConstants.WORKER_GROUP_ID;
    private final AndroidWorkerProofOptions options;
    private final ProofEvidence evidence;
    private final JsonHttpClient admin, user;
    private final List<String> seeds = seeds();

    private AndroidWorkerBatchChecksMain(AndroidWorkerProofOptions options) {
        this.options = options;
        evidence = new ProofEvidence(options, SCENARIO, options.phase());
        admin = new JsonHttpClient(options.serverBaseUrl(), options, headers("1000", "admin"));
        user = new JsonHttpClient(options.serverBaseUrl(), options, headers("1001", "operator"));
    }
    public static void main(String[] args) throws IOException {
        var proof = new AndroidWorkerBatchChecksMain(AndroidWorkerProofOptions.parse(args));
        try { proof.run(); }
        catch (ProofFailure failure) { proof.evidence.failure(failure); throw failure; }
        catch (RuntimeException | Error failure) { proof.evidence.unexpectedFailure(failure); throw failure; }
        finally { proof.evidence.write(); }
    }
    private void run() {
        var catalog = request(user, "GET", "/api/v1/app-checks/catalog", null, 200);
        var checks = rows(catalog.get("checks"));
        require(checks.size() == 1 && CHECK.equals(checks.getFirst().get("checkId")), "isolated-catalog");
        for (String path : List.of("/api/v1/messages/catalog", "/api/v1/sms/catalog"))
            require(admin.sendText("GET", path, null, "application/json", "scenario-absent").statusCode() == 404, "scenario-absent");
        var effective = object(request(admin, "GET", "/api/v1/app-checks/configuration", null, 200).get("effective"));
        evidence.check("configurationFingerprint", effective.get("fingerprint"));
        String taskId;
        if (options.phase().equals("initial")) {
            options.baselineFile(false);
            var device = new AndroidDeviceHostClient(new JsonHttpClient(options.deviceBaseUrl(), options, headers("1000", "admin")));
            var runtime = new AndroidRuntimeApiClient(admin);
            AndroidWorkerProofAssertions.awaitDeviceHealth(device, options.maximumWait());
            AndroidWorkerProofAssertions.requireDeviceEvents(device);
            var snapshot = AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), null);
            evidence.workerId(snapshot.workerId());
            AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), snapshot.workerId(), options.maximumWait());
            AndroidWorkerProofAssertions.awaitHot(runtime, snapshot.workerId(), options.maximumWait());
            runtime.requirePropertiesRelation(options.endpointManagerId(), snapshot.workerId());
            request(admin, "POST", "/api/v1/users/1001/credits/adjustments",
                    Map.of("requestId", UUID.randomUUID().toString(), "delta", 1000, "reason", "Android finite proof"), 200);
            var create = Map.of("requestId", options.proofId(), "checkId", CHECK);
            taskId = (String) request(user, "POST", "/api/v1/app-checks/tasks", create, 201).get("taskId");
            require(taskId.equals(request(user, "POST", "/api/v1/app-checks/tasks", create, 201).get("taskId")), "creation-replay");
            var imported = importSeeds(taskId);
            require(number(imported.get("addedCount")) == 123 && number(imported.get("itemCount")) == 123, "import-count");
            var repeated = importSeeds(taskId);
            require(number(repeated.get("addedCount")) == 0 && number(repeated.get("existingCount")) == 123, "import-replay");
            var quote = request(user, "GET", business(taskId) + "/credits", null, 200);
            require(number(quote.get("seedCount")) == 123 && number(quote.get("itemCount")) == 123
                    && number(quote.get("amount")) == 123 && number(quote.get("unitPrice")) == 1, "actual-price");
            var approval = Map.of("expectedItemCount", 123, "credits",
                    Map.of("requestId", UUID.randomUUID().toString(), "expectedSeedCount", 123, "expectedAmount", 123));
            request(user, "POST", task(taskId) + "/approve", approval, 200);
            request(user, "POST", task(taskId) + "/approve", approval, 200);
            ProofWait.until(options.maximumWait(),
                    () -> request(user, "GET", project(taskId), null, 200).get("scoreBand"),
                    "terminal"::equals, "batch-checks.terminal", "Finite task did not finish", taskId);
        } else if (options.phase().equals("restart")) {
            var baseline = ProofEvidence.readBaseline(options.baselineFile(true), options, SCENARIO, "initial");
            evidence.workerId(baseline.workerId());
            taskId = baseline.requiredCheck("taskId");
            require(effective.get("fingerprint").equals(baseline.requiredCheck("configurationFingerprint")), "configuration-restart");
            evidence.baselineIdentityMatched(true);
        } else throw new IllegalArgumentException("Batch Checks phase must be initial or restart");
        verify(taskId);
        evidence.check("taskId", taskId);
        evidence.check("seedCount", 123);
        evidence.check("itemCount", 123);
        evidence.check("chargedAmount", 123);
        evidence.check("poolQuery", "worker.any");
        evidence.check("rawResultCount", 123);
    }
    private void verify(String taskId) {
        var detail = request(user, "GET", project(taskId), null, 200);
        require("terminal".equals(detail.get("scoreBand")), "terminal-state");
        var descriptor = object(detail.get("task"));
        require(GROUP.equals(descriptor.get("workerGroupId")), "task-group");
        var metadata = object(descriptor.get("metadata"));
        require("1001".equals(metadata.get("userId")) && CHECK.equals(metadata.get("checkId")), "task-owner");
        require("CLOSE_WHEN_IDLE".equals(descriptor.get("idleDisposition")), "finite-kind");
        var targets = rows(descriptor.get("refill"));
        require(targets.size() == 1 && "any".equals(targets.getFirst().get("poolName")), "pool-supply");
        var expected = new LinkedHashMap<String,String>();
        for (var seed : seeds) expected.put(messageId(seed), seed);
        var ids = new ArrayList<>(expected.keySet());
        for (int from = 0; from < ids.size(); from += 100) {
            var batch = ids.subList(from, Math.min(ids.size(), from + 100));
            var results = ProofWait.until(options.maximumWait(),
                    () -> request(user, "POST", task(taskId) + "/results:load", batch, 200),
                    values -> values.keySet().equals(new HashSet<>(batch)) && values.values().stream()
                            .allMatch(value -> "succeeded".equals(object(value).get("status"))),
                    "batch-checks.raw-results", "Finite result content did not converge", taskId);
            var states = request(user, "POST", task(taskId) + "/items:states", batch, 200);
            require(results.keySet().equals(new HashSet<>(batch)) && states.keySet().equals(new HashSet<>(batch)), "identity-set");
            for (var id : batch) {
                var result = object(results.get(id));
                require("succeeded".equals(result.get("status")), "raw-success");
                var content = Jsons.parseObject((String) result.get("opaqueResultPayload"));
                require(content.equals(Map.of("algorithm", "MD5", "input", expected.get(id), "digest", md5(expected.get(id)))), "raw-content");
                var state = object(states.get(id));
                require("terminal".equals(state.get("band")) && "succeeded".equals(state.get("outcomeName")), "item-state");
            }
        }
        var raw = request(user, "GET", task(taskId) + "/results:preview", null, 200);
        require(Boolean.TRUE.equals(raw.get("truncated")) && rows(raw.get("results")).size() <= 100, "raw-preview-bound");
        for (var entry : rows(raw.get("results"))) {
            var item = object(entry.get("item"));
            String seed = expected.get(entry.get("messageId"));
            require(seed != null && EVENT.equals(item.get("eventCode")), "item-event");
            require(object(item.get("payload")).equals(Map.of("algorithm", "MD5", "value", seed)), "item-input");
            require(object(item.get("workerSelector")).equals(Map.of("executorName", "worker.any", "input", Map.of())), "item-pool-query");
        }
        evidence.check("rawInputPreviewCount", rows(raw.get("results")).size());
        var preview = request(user, "GET", business(taskId) + "/results", null, 200);
        require(Boolean.TRUE.equals(preview.get("truncated")) && !rows(preview.get("results")).isEmpty()
                && rows(preview.get("results")).size() <= 100, "business-preview-bound");
        for (var row : rows(preview.get("results"))) {
            String seed = expected.get(row.get("messageId"));
            require(seed != null && seed.equals(row.get("seed")) && !row.containsKey("contentError")
                    && "succeeded".equals(row.get("resultStatus"))
                    && Boolean.valueOf(md5(seed).startsWith("0")).equals(row.get("matched"))
                    && md5(seed).equals(object(row.get("details")).get("md5")), "business-answer");
        }
        for (var filter : List.of("all", "matched", "unmatched")) {
            var response = user.sendText("POST", business(taskId) + "/results:export?filter=" + filter, null, "application/json", "csv-export");
            require(response.statusCode() == 200, "csv-status");
            var csv = csv(response.body());
            require(csv.removeFirst().equals(List.of("种子", "判定", "检查类型", "执行配置", "MD5 摘要")), "csv-header");
            var expectedSeeds = new HashSet<>(seeds.stream().filter(seed -> filter.equals("all")
                    || md5(seed).startsWith("0") == filter.equals("matched")).toList());
            require(csv.size() == expectedSeeds.size() && Integer.parseInt(response.headers().firstValue("X-Export-Count").orElse("-1")) == expectedSeeds.size(), "csv-count");
            for (var row : csv) require(row.size() == 5 && expectedSeeds.remove(row.get(0))
                    && row.get(1).equals(md5(row.get(0)).startsWith("0") ? "满足" : "不满足")
                    && row.get(4).equals(md5(row.get(0))), "csv-answer");
            require(expectedSeeds.isEmpty(), "csv-complete");
            evidence.check("csv-" + filter, csv.size());
        }
        var account = request(user, "GET", "/api/v1/users/1001/credits", null, 200);
        require(number(account.get("balance")) == 877 && number(account.get("latestSequence")) == 2, "one-debit");
        var transactions = rows(request(user, "GET", "/api/v1/users/1001/credits/transactions", null, 200).get("entries"));
        var debits = transactions.stream().filter(row -> row.get("business") instanceof Map).toList();
        require(debits.size() == 1 && number(debits.getFirst().get("delta")) == -123
                && taskId.equals(object(debits.getFirst().get("business")).get("taskId")), "debit-association");
    }
    private Map<String,Object> importSeeds(String taskId) {
        var response = user.sendText("POST", business(taskId) + "/seeds:import", String.join("\n", seeds), "text/plain; charset=utf-8", "import-seeds");
        require(response.statusCode() == 200, "import-status");
        return Jsons.parseObject(response.body());
    }
    private static Map<String,Object> request(JsonHttpClient client, String method, String path, Object body, int expected) {
        var result = client.send(method, path, body, "batch-checks");
        require(result.statusCode() == expected, "http-" + expected + "-actual-" + result.statusCode());
        return result.body();
    }
    static List<String> seeds() {
        var values = new ArrayList<>(List.of("a", "abc", "中文"));
        for (int i = 0; i < 120; i++) values.add("android-proof-" + i);
        return List.copyOf(values);
    }
    static String messageId(String seed) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("app-checks/v1/text", seed)) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return "seed-" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String md5(String seed) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static List<List<String>> csv(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int i = text.startsWith("\uFEFF") ? 1 : 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = !quoted;
            } else if (!quoted && (c == ',' || c == '\n')) {
                row.add(cell.toString()); cell.setLength(0);
                if (c == '\n') { rows.add(List.copyOf(row)); row.clear(); }
            } else if (c != '\r' || quoted) cell.append(c);
        }
        require(!quoted && cell.isEmpty() && row.isEmpty(), "csv-framing");
        return rows;
    }
    private static Map<String,String> headers(String user, String role) { return Map.of("X-XA-Dev-User", Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8)), "X-XA-Dev-Role", role); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String task(String id) { return "/api/v1/tasks/" + id; }
    private static String business(String id) { return "/api/v1/app-checks/tasks/" + id; }
    private static String project(String id) { return "/api/v1/projects/app-checks/tasks/" + id; }
    private static void require(boolean condition, String name) {
        if (!condition) throw new ProofFailure("batch-checks." + name, "Batch Checks assertion failed: " + name);
    }
}
