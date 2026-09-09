package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

final class CallApi implements AutoCloseable {
    static final String GROUP = "scenario-string-utils-workers";
    static final String INPUT = "x".repeat(64);
    enum ExpectedResult { MD5, DELAY }
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder().executor(executor)
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
    private final String runtime;
    private final String lab;

    CallApi(String runtime, String lab) { this.runtime = runtime; this.lab = lab; }

    Map<String, Object> post(String path, Object body) throws Exception { return json(runtime + path, body); }
    Map<String, Object> workers() throws Exception { return json(lab + "/lab/v1/workers", null); }

    private Map<String, Object> json(String url, Object body) throws Exception {
        return json(url, body, Duration.ofSeconds(5));
    }

    private Map<String, Object> json(String url, Object body, Duration timeout) throws Exception {
        var response = send(url, body, timeout);
        if (response.statusCode() != 200) throw new IllegalStateException(
                "Observation/action HTTP " + response.statusCode() + " at " + URI.create(url).getPath());
        return Jsons.parseObject(response.body());
    }

    private HttpResponse<String> send(String url, Object body) throws Exception {
        return send(url, body, Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(String url, Object body, Duration timeout) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    CallLoad.Reply call(String task, String id, String worker) throws Exception {
        var item = new LinkedHashMap<String, Object>();
        item.put("messageId", id);
        item.put("eventCode", "extension.worker.string.md5");
        item.put("payload", Map.of("value", INPUT));
        item.put("ttlMillis", 120_000);
        item.put("workerSelector", worker == null ? List.of() : List.of("workerId", "$eq", worker));
        var response = send(runtime + "/api/v1/tasks/" + task + "/items:call",
                Map.of("items", List.of(item), "waitTimeoutMillis", 1_000));
        if (Set.of(400, 404, 405, 415, 422).contains(response.statusCode()))
            return new CallLoad.Reply(response.statusCode(), CallLoad.Outcome.PROTOCOL_ERROR);
        if (response.statusCode() != 200) return new CallLoad.Reply(response.statusCode(), CallLoad.Outcome.UNKNOWN);
        try {
            var results = Jsons.parseObject(response.body());
            if (!results.keySet().equals(Set.of(id))) throw new IllegalArgumentException();
            return new CallLoad.Reply(200, switch (checkedResult(results.get(id), ExpectedResult.MD5)) {
                case "succeeded" -> CallLoad.Outcome.SUCCEEDED;
                case "failed" -> CallLoad.Outcome.FAILED;
                default -> CallLoad.Outcome.NOT_OBSERVED;
            });
        } catch (RuntimeException error) {
            throw new CallLoad.ProtocolFailure("Call response shape or correlation changed");
        }
    }

    CallLoad.Reply directCall(String worker) throws Exception {
        var response = send(runtime + "/api/v1/worker-delivery/endpoint-managers/scenario-websocket/direct-calls",
                Map.of("workerGroupId", GROUP, "workerPayloads", Map.of(worker, Jsons.toJson(Map.of("value", INPUT))),
                        "messageType", "extension.worker.string.md5", "waitTimeoutMillis", 1_000));
        int status = response.statusCode();
        if (status == 429) return new CallLoad.Reply(status, CallLoad.Outcome.REJECTED, null, "http-429");
        if (Set.of(400, 401, 403, 404, 405, 415, 422).contains(status))
            return new CallLoad.Reply(status, CallLoad.Outcome.PROTOCOL_ERROR);
        if (status != 200) return new CallLoad.Reply(status, CallLoad.Outcome.UNKNOWN, null, "http-" + status);
        try {
            return checkedDirectResult(Jsons.parseObject(response.body()), worker);
        } catch (RuntimeException error) {
            throw new CallLoad.ProtocolFailure("Direct Call response shape, target or result changed");
        }
    }

    static CallLoad.Reply checkedDirectResult(Map<String, Object> response, String worker) {
        if (!response.keySet().equals(Set.of("directCallId", "status", "results")))
            throw new CallLoad.ProtocolFailure("Unexpected Direct Call envelope");
        String id = string(response, "directCallId");
        var results = object(response.get("results"));
        if (!results.keySet().equals(Set.of(worker))) throw new CallLoad.ProtocolFailure("Direct target changed");
        var target = object(results.get(worker));
        String state = string(target, "status");
        if (!java.util.Objects.equals(response.get("status"), state.equals("observed") ? "observed" : "partial"))
            throw new CallLoad.ProtocolFailure("Direct aggregate status disagrees with target");
        if (state.equals("observed")) {
            if (!target.keySet().equals(Set.of("status", "messageType", "diagnosticCode", "opaqueResultPayload")))
                throw new CallLoad.ProtocolFailure("Unexpected observed Direct fields");
            String event = string(target, "messageType");
            if (!(target.get("diagnosticCode") instanceof String code))
                throw new CallLoad.ProtocolFailure("Missing Direct diagnostic");
            if (!Set.of("platform.worker.command.succeeded", "platform.worker.command.failed").contains(event))
                throw new CallLoad.ProtocolFailure("Unexpected Worker result event");
            boolean succeeded = event.equals("platform.worker.command.succeeded");
            if (!(target.get("opaqueResultPayload") instanceof String)) throw new CallLoad.ProtocolFailure("Missing Direct payload");
            if (succeeded) checkedResult(Map.of("status", "succeeded",
                    "opaqueResultPayload", target.get("opaqueResultPayload")), ExpectedResult.MD5);
            return new CallLoad.Reply(200, succeeded ? CallLoad.Outcome.SUCCEEDED : CallLoad.Outcome.FAILED, id, code);
        }
        if (!target.keySet().equals(Set.of("status", "reason"))) throw new CallLoad.ProtocolFailure("Unexpected Direct reason fields");
        String reason = string(target, "reason");
        if (state.equals("rejected") && reason.equals("command-slot-occupied"))
            return new CallLoad.Reply(200, CallLoad.Outcome.REJECTED, id, reason);
        if (state.equals("unobserved") && reason.equals("timeout"))
            return new CallLoad.Reply(200, CallLoad.Outcome.TIMED_OUT, id, reason);
        if (state.equals("unobserved") && reason.equals("submission-unknown"))
            return new CallLoad.Reply(200, CallLoad.Outcome.UNKNOWN, id, reason);
        // Missing/bad Binding or shutdown in this fixed live world is a failed prerequisite.
        throw new CallLoad.ProtocolFailure("Unexpected Direct status or reason");
    }

    Map<String, String> results(String task, List<String> ids) throws Exception {
        return results(task, ids, ExpectedResult.MD5);
    }

    Map<String, String> results(String task, List<String> ids, ExpectedResult expected) throws Exception {
        return results(task, ids, expected, Duration.ofSeconds(5));
    }

    Map<String, String> results(String task, List<String> ids, ExpectedResult expected, Duration timeout) throws Exception {
        if (ids.isEmpty() || ids.size() > 1_000) throw new IllegalArgumentException("Result page must contain 1..1000 IDs");
        var raw = json(runtime + "/api/v1/tasks/" + task + "/results:load", ids, timeout);
        if (!raw.keySet().equals(Set.copyOf(ids))) throw new CallLoad.ProtocolFailure("Result identity set changed");
        var result = new LinkedHashMap<String, String>();
        ids.forEach(id -> result.put(id, checkedResult(raw.get(id), expected)));
        return result;
    }

    static String checkedResult(Object raw, ExpectedResult expected) {
        String status = resultStatus(raw);
        if (!status.equals("succeeded")) return status;
        String payload = string(object(raw), "opaqueResultPayload");
        boolean valid;
        try {
            valid = expected == ExpectedResult.DELAY ? payload.equals("null")
                    : Jsons.parseObject(payload).equals(Map.of("input", INPUT, "valid", true,
                            "md5", "c1bb4f81d892b2d57947682aeb252456"));
        } catch (RuntimeException error) { valid = false; }
        if (!valid) throw new CallLoad.ProtocolFailure("Handler result did not match the fixture");
        return status;
    }

    static String resultStatus(Object raw) {
        var value = object(raw);
        String status = string(value, "status");
        if (!Set.of("succeeded", "failed", "not_observed").contains(status))
            throw new CallLoad.ProtocolFailure("Unknown Result status");
        Set<String> fields = status.equals("succeeded") ? Set.of("status", "opaqueResultPayload") : Set.of("status");
        if (!value.keySet().equals(fields) || (status.equals("succeeded") && !(value.get("opaqueResultPayload") instanceof String)))
            throw new CallLoad.ProtocolFailure("Result payload boundary changed");
        return status;
    }

    static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw new CallLoad.ProtocolFailure("Expected object");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, v));
        return result;
    }
    static String string(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String text) || text.isBlank()) throw new CallLoad.ProtocolFailure("Missing " + key);
        return text;
    }

    @Override public void close() throws InterruptedException {
        http.shutdownNow();
        executor.shutdownNow();
        if (!http.awaitTermination(Duration.ofSeconds(5)) || !executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("HTTP resources did not stop");
    }
}
