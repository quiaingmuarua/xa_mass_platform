package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.util.*;

/** Raw finite Task witness for host Group window bindings, without any Scenario. */
public final class AndroidWorkerWindowPoolMain {
    private static final String GROUP = AndroidWorkerProofConstants.WORKER_GROUP_ID;
    private static final String POOL = "device-window", FUNCTION = "worker.device.available";
    private final AndroidWorkerProofOptions options;
    private final ProofEvidence evidence;
    private final JsonHttpClient http;

    private AndroidWorkerWindowPoolMain(AndroidWorkerProofOptions options) {
        this.options = options;
        evidence = new ProofEvidence(options, "window-pool", options.phase());
        http = new JsonHttpClient(options.serverBaseUrl(), options, Map.of("X-XA-Dev-User", "MTAwMA", "X-XA-Dev-Role", "admin"));
    }
    public static void main(String[] args) throws IOException {
        var proof = new AndroidWorkerWindowPoolMain(AndroidWorkerProofOptions.parse(args));
        try { proof.run(); }
        catch (ProofFailure failure) { proof.evidence.failure(failure); throw failure; }
        catch (RuntimeException | Error failure) { proof.evidence.unexpectedFailure(failure); throw failure; }
        finally { proof.evidence.write(); }
    }
    private void run() {
        require(options.phase().equals("initial"), "phase");
        for (String path : List.of("/api/v1/app-checks/catalog", "/api/v1/messages/catalog", "/api/v1/sms/catalog"))
            require(http.sendText("GET", path, null, "application/json", "scenario-absent").statusCode() == 404, "scenario-absent");
        var bindings = request("GET", "/api/v1/runtime-view/worker-groups/" + GROUP + "/bindings", null);
        var pools = rows(bindings.get("fixedWindowPools"));
        require(pools.size() == 1 && POOL.equals(pools.getFirst().get("poolName"))
                && FUNCTION.equals(pools.getFirst().get("functionName"))
                && number(pools.getFirst().get("windowMillis")) == 60000 && number(pools.getFirst().get("maxCount")) == 10, "window-binding");
        require(object(pools.getFirst().get("feedback")).get("messageEvents").equals(List.of("extension.worker.android.string.digest")), "paired-feedback");
        var device = new AndroidDeviceHostClient(new JsonHttpClient(options.deviceBaseUrl(), options, Map.of()));
        var runtime = new AndroidRuntimeApiClient(http);
        AndroidWorkerProofAssertions.awaitDeviceHealth(device, options.maximumWait());
        AndroidWorkerProofAssertions.requireDeviceEvents(device);
        String worker = AndroidWorkerProofAssertions.awaitRunning(device, options.maximumWait(), null).workerId();
        evidence.workerId(worker);
        AndroidWorkerProofAssertions.awaitConnected(runtime, options.endpointManagerId(), worker, options.maximumWait());
        AndroidWorkerProofAssertions.awaitHot(runtime, worker, options.maximumWait());
        runtime.requirePropertiesRelation(options.endpointManagerId(), worker);
        String task = (String) request("POST", "/api/v1/tasks", Map.of("requestId", options.proofId(),
                "projectId", "default", "workerGroupId", GROUP,
                "refill", List.of(Map.of("poolName", POOL, "target", Map.of(), "count", 100)))).get("taskId");
        var seeds = List.of("a", "abc", "中文");
        var expected = List.of("0cc175b9c0f1b6a831c399e269772661", "900150983cd24fb0d6963f7d28e17f72", "a7bac2239fcdcb3a067903d8077c4a07");
        var ids = List.of("one", "two", "three");
        var items = new ArrayList<Map<String,Object>>();
        for (int i = 0; i < ids.size(); i++) items.add(Map.of("messageId", ids.get(i),
                "eventCode", "extension.worker.android.string.digest", "payload", Map.of("algorithm", "MD5", "value", seeds.get(i)),
                "workerSelector", Map.of("executorName", FUNCTION, "input", Map.of())));
        request("POST", task(task) + "/items", items);
        request("POST", task(task) + "/approve", Map.of("expectedItemCount", 3));
        ProofWait.until(options.maximumWait(), () -> request("GET", "/api/v1/projects/default/tasks/" + task, null).get("scoreBand"),
                "terminal"::equals, "window-pool.terminal", "Finite Task did not finish", task);
        var results = ProofWait.until(options.maximumWait(), () -> request("POST", task(task) + "/results:load", ids),
                value -> value.keySet().equals(Set.copyOf(ids)) && value.values().stream().allMatch(row -> "succeeded".equals(object(row).get("status"))),
                "window-pool.results", "Finite Result content did not converge", task);
        var states = request("POST", task(task) + "/items:states", ids);
        for (int i = 0; i < ids.size(); i++) {
            var content = Jsons.parseObject((String) object(results.get(ids.get(i))).get("opaqueResultPayload"));
            require(content.equals(Map.of("algorithm", "MD5", "input", seeds.get(i), "digest", expected.get(i))), "raw-digest");
            var state = object(states.get(ids.get(i)));
            require("terminal".equals(state.get("band")) && "succeeded".equals(state.get("outcomeName")), "item-terminal");
        }
        var preview = rows(request("GET", task(task) + "/results:preview", null).get("results"));
        require(preview.size() == 3, "preview");
        for (var row : preview) require(object(object(row.get("item")).get("workerSelector"))
                .equals(Map.of("executorName", FUNCTION, "input", Map.of())), "actual-pool-query");
        var properties = ProofWait.until(options.maximumWait(), () -> {
            // A controlled one-Worker fixture: this preview is not fleet enumeration.
            var workers = rows(request("POST", "/api/v1/runtime-view/worker-groups/" + GROUP + "/workers:preview", 100).get("workers"));
            require(workers.size() == 1 && worker.equals(workers.getFirst().get("workerId")), "controlled-worker");
            return object(workers.getFirst().get("platformProperties"));
        }, values -> values.get("deviceAssignmentCount") instanceof Number n && n.longValue() > 0
                && values.get("deviceAssignedAt") instanceof Number t && t.longValue() > 0,
                "window-pool.feedback", "Window feedback was not observed", worker);
        var exported = http.sendText("POST", task(task) + "/results:export", null, "application/json", "raw-export");
        require(exported.statusCode() == 200 && exported.body().lines().count() == 3, "complete-raw-export");
        evidence.check("taskId", task);
        evidence.check("poolName", POOL);
        evidence.check("query", FUNCTION);
        evidence.check("rawResults", 3);
        evidence.check("jsonlRows", 3);
        evidence.check("observedWindowCount", number(properties.get("deviceAssignmentCount")));
    }
    private Map<String,Object> request(String method, String path, Object body) {
        var response = http.send(method, path, body, "window-pool");
        require(response.statusCode() >= 200 && response.statusCode() < 300, "http-" + response.statusCode());
        return response.body();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String task(String id) { return "/api/v1/tasks/" + id; }
    private static void require(boolean condition, String name) {
        if (!condition) throw new ProofFailure("window-pool." + name, "Group window assertion failed: " + name);
    }
}
