package com.xa.mass.integration.workerloadedrecovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerExternalCapacityTest {
    @TempDir Path temporary;
    private HttpServer server;
    private ExecutorService executor;
    private LoadedRecoveryApiClient client;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Set<String> approved = ConcurrentHashMap.newKeySet();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, String>> tasks = new ConcurrentHashMap<>();
    private final List<String> operations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger nextTask = new AtomicInteger();
    private final AtomicInteger appendCalls = new AtomicInteger();
    private boolean failAppend, failCreate, corruptResult, neverTerminal, missingAny;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new LoadedRecoveryApiClient(new LoadedRecoveryHttpClient(origin, Duration.ofSeconds(2), Map.of()),
                new LoadedRecoveryHttpClient(origin, Duration.ofSeconds(2), Map.of("Authorization", "Bearer xak_private_test")));
    }

    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test void comparesTwoFiniteWorldsAndVerifiesEveryResultAfterTerminalObservation() throws Exception {
        Map<String, Object> summary = run();
        assertThat(summary.get("status")).isEqualTo("passed");
        assertThat(summary.get("knownTaskIds")).asList().hasSize(20);
        assertThat(summary.get("unknownWrites")).asList().isEmpty();
        assertThat(closed).isEmpty();
        List<Object> cases = LoadedRecoveryJson.array(summary.get("cases"), "cases");
        assertThat(cases).hasSize(2);
        for (Object value : cases) {
            Map<String, Object> result = LoadedRecoveryJson.object(value, "case");
            assertThat(result).containsEntry("verifiedItems", 30).containsEntry("terminalTaskCount", 10);
            assertThat(result).containsKeys("preloadMillis", "verificationMillis", "exportMillis", "tasks");
            assertThat(LoadedRecoveryJson.object(result.get("preWorkStability"), "gate"))
                    .containsEntry("qualifyingScans", 3);
        }
        assertThat(operations.indexOf("stop")).isGreaterThan(operations.indexOf("export:task-10"));
        assertThat(operations.indexOf("approve:task-1")).isGreaterThan(operations.indexOf("append:task-10"));
        assertThat(operations.indexOf("approve:task-11")).isGreaterThan(operations.indexOf("append:task-20"));
        assertThat(operations.indexOf("export:task-1")).isGreaterThan(operations.indexOf("terminal:task-10"));
        String receipts = Files.readString(temporary.resolve("receipts.jsonl"));
        assertThat(receipts).contains("\"status\":\"sending\"", "\"status\":\"confirmed\"")
                .doesNotContain("xak_private_test", "external-capacity:", "opaqueResultPayload", "workers.jsonl");
        assertThat(Files.exists(temporary.resolve("private/worker-identities.json"))).isTrue();
    }

    @Test void missingGroupCapabilityFailsBeforeStartingAnyWorker() throws Exception {
        missingAny = true;
        Map<String, Object> summary = run();
        assertThat(summary).containsEntry("status", "failed").containsEntry("failureKind", "preflight-any-pool-missing");
        assertThat(running).isEmpty();
        assertThat(tasks).isEmpty();
    }

    @Test void ambiguousAppendIsNeverReplayedOrApprovedAndOnlyKnownTasksAreClosed() throws Exception {
        failAppend = true;
        Map<String, Object> summary = run();
        assertThat(summary.get("status")).isEqualTo("unconfirmed");
        assertThat(summary.get("unknownWrites")).asList().isNotEmpty();
        assertThat(approved).isEmpty();
        assertThat(appendCalls.get()).isBetween(1, 8);
        assertThat(closed).hasSize(10).containsExactlyInAnyOrderElementsOf(tasks.keySet());
        assertThat(operations).doesNotContain("stop");
        assertThat(Files.readString(temporary.resolve("receipts.jsonl"))).doesNotContain("SECRET_RESPONSE");
    }

    @Test void ambiguousCreateRetainsRequestIdentityWithoutInventingATaskToClean() throws Exception {
        failCreate = true;
        Map<String, Object> summary = run();
        assertThat(summary.get("status")).isEqualTo("unconfirmed");
        assertThat(summary.get("knownTaskIds")).asList().isEmpty();
        assertThat(summary.get("unknownWrites")).asList().hasSize(1);
        assertThat(closed).isEmpty();
        assertThat(operations.stream().filter("create"::equals)).hasSize(1);
        assertThat(Files.readString(temporary.resolve("receipts.jsonl"))).contains("requestId");
    }

    @Test void corruptSuccessfulExportCannotPassTheIndependentOracle() throws Exception {
        corruptResult = true;
        Map<String, Object> summary = run();
        assertThat(summary).containsEntry("status", "failed").containsEntry("failureKind", "md5-result-mismatch");
        assertThat(summary.get("cases")).asList().isEmpty();
        assertThat(closed).isEmpty(); // Point-read confirms all created Tasks already terminal.
        assertThat(operations).doesNotContain("stop");
    }

    @Test void successfulStatisticsDoNotSubstituteForTaskTerminalState() throws Exception {
        neverTerminal = true;
        Map<String, Object> summary = run();
        assertThat(summary).containsEntry("status", "failed").containsEntry("failureKind", "task-terminal-timeout");
        assertThat(operations).noneMatch(value -> value.startsWith("export:"));
        assertThat(closed).hasSize(10);
    }

    @Test void validatesOriginsCountsAndPrivateKeyWithoutEchoingSuppliedSecrets() throws Exception {
        var args = arguments();
        assertThat(WorkerExternalCapacityOptions.parse(args).preparedWorkers()).isEqualTo(15_000);
        for (String invalid : List.of("http://runtime.example", "https://secret@runtime.example", "https://runtime.example/path",
                "https://runtime.example?secret=value", "https://runtime.example#secret")) {
            args[0] = "--server-base-url=" + invalid;
            assertThatThrownBy(() -> WorkerExternalCapacityOptions.parse(args)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret");
        }
        Files.writeString(temporary.resolve("key"), "xak_private_test\n");
        var options = WorkerExternalCapacityOptions.parse(arguments());
        assertThat(options.readAccessKey()).isEqualTo("xak_private_test");
        Files.writeString(temporary.resolve("key"), "xak_private_test\r\nAuthorization: secret");
        assertThatThrownBy(options::readAccessKey).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }

    @Test void rejectsWrongInputAndUnexpectedExportIdentitiesEvenWithCorrectLookingDigest() {
        Map<String, String> inputs = Map.of("one", "abc");
        WorkerExternalCapacityMain.verifyMd5(inputs, "one", "{\"input\":\"abc\",\"valid\":true,\"md5\":\"900150983cd24fb0d6963f7d28e17f72\"}");
        assertThatThrownBy(() -> WorkerExternalCapacityMain.verifyMd5(inputs, "two", "{}"))
                .hasMessage("unexpected-export-identity");
        assertThatThrownBy(() -> WorkerExternalCapacityMain.verifyMd5(inputs, "one", "{\"input\":\"other\",\"valid\":true,\"md5\":\"900150983cd24fb0d6963f7d28e17f72\"}"))
                .hasMessage("md5-result-mismatch");
    }

    private String[] arguments() {
        return new String[]{"--server-base-url=https://runtime.example", "--lab-base-url=http://127.0.0.1:1234",
                "--project-id=external-project", "--worker-group-id=external-group", "--endpoint-manager-id=external-adapter",
                "--access-key-file=" + temporary.resolve("key"), "--output-dir=" + temporary.resolve("output")};
    }

    private Map<String, Object> run() throws IOException {
        WorkerExternalCapacityMain.prepareOutput(temporary);
        var options = new WorkerExternalCapacityOptions(URI.create("https://runtime.example"), URI.create("http://127.0.0.1:1234"),
                "external-project", "external-group", "external-adapter", temporary.resolve("unused-key"), temporary,
                2, 1, 2, 1, 3, Duration.ofMillis(1), Duration.ofSeconds(3), Duration.ofSeconds(neverTerminal ? 1 : 15),
                Duration.ofSeconds(2), Duration.ofMillis(2));
        return new WorkerExternalCapacityMain(options, client).run();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.startsWith("/api/")) assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer xak_private_test");
            else assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            if (path.equals("/api/v1/runtime-view/worker-groups/external-group/bindings")) {
                respond(exchange, 200, Map.of("workerGroupId", "external-group",
                        "workerGroup", Map.of("workerGroupId", "external-group", "eventCodes", List.of("extension.worker.string.md5")),
                        "matching", Map.of("pools", missingAny ? List.of() : List.of("any"),
                                "functions", List.of(Map.of("name", "worker.any", "poolName", "any"))),
                        "projects", List.of(Map.of("projectId", "external-project"))));
            } else if (path.equals("/lab/v1/workers")) {
                List<Map<String, Object>> workers = new ArrayList<>();
                for (String key : List.of("workers.jsonl:1", "workers.jsonl:2")) {
                    Map<String, Object> worker = new LinkedHashMap<>(Map.of("workerGroupId", "external-group", "labWorkerKey", key,
                            "desiredState", running.contains(key) ? "RUNNING" : "STOPPED", "runtimeState", running.contains(key) ? "RUNNING" : "STOPPED"));
                    worker.put("workerId", running.contains(key) ? "id-" + key : null);
                    workers.add(worker);
                }
                respond(exchange, 200, Map.of("workers", workers));
            } else if (path.endsWith(":start")) {
                String key = path.substring(path.lastIndexOf('/') + 1, path.length() - ":start".length());
                running.add(key); operations.add("start:" + key);
                respond(exchange, 202, Map.of());
            } else if (path.equals("/lab/v1/workers:stop")) {
                List<Object> body = Jsons.parseArray(raw);
                body.forEach(value -> running.remove(LoadedRecoveryJson.object(value, "worker").get("labWorkerKey")));
                operations.add("stop"); respond(exchange, 202, Map.of("acceptedCount", body.size()));
            } else if (path.endsWith(":network-observe") || path.endsWith(":scheduling-observe")) {
                Map<String, String> states = new LinkedHashMap<>();
                for (Object id : Jsons.parseArray(raw)) states.put((String) id, path.endsWith(":network-observe")
                        ? running.contains(((String) id).substring(3)) ? "connected" : "disconnected" : "recovery");
                respond(exchange, 200, Map.of("statesByWorkerId", states));
            } else if (path.equals("/api/v1/tasks")) {
                operations.add("create");
                var body = Jsons.parseObject(raw);
                assertThat(body.get("projectId")).isEqualTo("external-project");
                assertThat(body.get("workerGroupId")).isEqualTo("external-group");
                if (failCreate) { respond(exchange, 503, Map.of("message", "SECRET_RESPONSE")); return; }
                String id = "task-" + nextTask.incrementAndGet(); tasks.put(id, new ConcurrentHashMap<>());
                respond(exchange, 200, Map.of("taskId", id));
            } else if (path.endsWith("/tasks:stats")) {
                Map<String, Object> stats = new LinkedHashMap<>();
                for (Object id : Jsons.parseArray(raw)) stats.put((String) id, Map.of("total", tasks.get(id).size(),
                        "countsByTag", Map.of(approved.contains(id) ? "6" : "1", tasks.get(id).size())));
                respond(exchange, 200, stats);
            } else if (path.startsWith("/api/v1/projects/external-project/tasks/")) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                boolean terminal = approved.contains(id) && !neverTerminal;
                if (terminal) operations.add("terminal:" + id);
                respond(exchange, 200, Map.of("taskId", id, "scoreBand", terminal ? "terminal" : "running"));
            } else {
                String[] parts = path.split("/"); String id = parts[4]; String action = parts[5];
                switch (action) {
                    case "items" -> {
                        appendCalls.incrementAndGet(); operations.add("append:" + id);
                        if (failAppend) { respond(exchange, 503, Map.of("message", "SECRET_RESPONSE")); return; }
                        Map<String, Object> response = new LinkedHashMap<>();
                        for (Object item : Jsons.parseArray(raw)) {
                            var object = LoadedRecoveryJson.object(item, "item"); String messageId = (String) object.get("messageId");
                            tasks.get(id).put(messageId, (String) LoadedRecoveryJson.object(object.get("payload"), "payload").get("value"));
                            response.put(messageId, Map.of("status", "applied"));
                        }
                        respond(exchange, 200, response);
                    }
                    case "approve" -> { approved.add(id); operations.add("approve:" + id); respond(exchange, 200, Map.of("status", "applied")); }
                    case "close" -> { closed.add(id); respond(exchange, 200, Map.of("status", "applied")); }
                    case "items:states" -> {
                        Map<String, Object> states = new LinkedHashMap<>();
                        for (Object messageId : Jsons.parseArray(raw)) states.put((String) messageId, Map.of("band", "terminal", "tag", 6, "timeMillis", 100));
                        respond(exchange, 200, states);
                    }
                    case "results:export" -> {
                        operations.add("export:" + id);
                        StringBuilder jsonl = new StringBuilder();
                        tasks.get(id).forEach((messageId, input) -> jsonl.append(Jsons.toJson(Map.of("messageId", messageId,
                                "opaqueResultPayload", Jsons.toJson(Map.of("input", input, "valid", true, "md5", corruptResult ? "bad" : digest(input)))))).append('\n'));
                        byte[] body = jsonl.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
                    }
                    default -> throw new AssertionError("Unexpected route " + path);
                }
            }
        } catch (Throwable error) {
            respond(exchange, 500, Map.of("error", "test-fixture-failed"));
        }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Jsons.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
}
