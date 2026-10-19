package com.xa.mass.serverboot;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workersimulator.appchecks.AppRegistrationCheck;
import com.xa.mass.server.assembly.pacer.WorkerObservationWitness;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.*;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/Redis/Worker proof. The attempt witness exists only in this test assembly. */
@Tag("scenario-composition")
class AppChecksIntegrationTest {
    @Test @Timeout(180) @SuppressWarnings("unchecked")
    void creditsUseActualGroupedSeedsAndSurviveUnknownCommitsRestartAndTaskCleanup() throws Exception {
        try (var world = new World()) {
            world.fund("1001", -999900);
            assertThat((Map<String,Object>) world.get("/v3/api-docs").get("paths"))
                    .containsKey("/api/v1/app-checks/tasks/{taskId}/credits");
            String createdBody = Jsons.toJson(Map.of("requestId", "credit-task", "checkId", "md5-prefix"));
            var created = world.postAs("/api/v1/app-checks/tasks", createdBody, "application/json", "1001", "operator");
            assertThat(created.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(created.body()).get("taskId");
            String seeds = IntStream.range(0, 23).mapToObj(i -> "seed-" + i).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(world.postAs("/api/v1/app-checks/tasks/" + id + "/seeds:import", seeds, "text/plain", "1001", "operator").statusCode()).isEqualTo(200);
            assertThat(world.postAs("/api/v1/app-checks/tasks/" + id + "/seeds:import", seeds, "text/plain", "1001", "operator").statusCode()).isEqualTo(200);
            var quote = world.get("/api/v1/app-checks/tasks/" + id + "/credits");
            assertThat(quote).containsEntry("itemCount", 3L).containsEntry("seedCount", 23L).containsEntry("amount", 23L).containsEntry("userId", "1001");
            assertThat(world.http.send(world.request("/api/v1/app-checks/tasks/" + id + "/credits", "1002", "operator").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(world.post("/api/v1/tasks", Jsons.toJson(Map.of("requestId", "raw-bypass", "projectId", "app-checks", "workerGroupId", "demo-sim", "refill", List.of())), "application/json").statusCode()).isEqualTo(403);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", "{\"expectedItemCount\":3}", "application/json").statusCode()).isEqualTo(400);
            var confirmation = world.confirmation(id, "proof", "admin");
            var forged = new LinkedHashMap<>(confirmation); forged.put("expectedAmount", 22);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", Jsons.toJson(Map.of("expectedItemCount", 3, "credits", forged)), "application/json").statusCode()).isEqualTo(409);
            String body = Jsons.toJson(Map.of("expectedItemCount", 3, "credits", confirmation));
            world.loseDebitResponse.set(true);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", body, "application/json").statusCode()).isEqualTo(503);
            assertThat(world.get("/api/v1/users/1001/credits")).containsEntry("balance", 77L);
            assertThat(world.get("/api/v1/projects/app-checks/tasks/" + id)).containsEntry("scoreBand", "pre_review");
            world.restart();
            assertThat(world.get("/api/v1/app-checks/tasks/" + id + "/credits?requestId=" + confirmation.get("requestId"))).containsEntry("status", "charged");
            assertThat(world.postAs("/api/v1/app-checks/tasks/" + id + "/seeds:import", "new-seed", "text/plain", "1001", "operator").statusCode()).isEqualTo(400);
            world.loseApprovalResponse.set(true);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", body, "application/json").statusCode()).isEqualTo(503);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", body, "application/json").statusCode()).isEqualTo(200);
            assertThat(world.approve(id, 3).statusCode()).isEqualTo(200);
            assertThat(world.get("/api/v1/users/1001/credits")).containsEntry("balance", 77L);
            assertThat(world.post("/api/v1/tasks/" + id + "/close", "", "application/json").statusCode()).isEqualTo(200);
            assertThat(world.get("/api/v1/users/1001/credits")).containsEntry("balance", 77L);
            var configuration = world.get("/api/v1/app-checks/configuration");
            var saved = (Map<String,Object>) configuration.get("saved");
            var config = (Map<String,Object>) saved.get("configuration");
            ((Map<String,Object>) ((Map<String,Object>) config.get("checks")).get("md5-prefix")).put("unitPrice", 2);
            assertThat(world.putConfiguration(Map.of("expectedFingerprint", saved.get("fingerprint"), "configuration", config)).statusCode()).isEqualTo(200);
            world.restart();
            assertThat(world.get("/api/v1/app-checks/tasks/" + id + "/credits")).containsEntry("unitPrice", 1L).containsEntry("amount", 23L);
            var fresh = world.postAs("/api/v1/app-checks/tasks", createdBody.replace("credit-task", "second-credit-task"), "application/json", "1001", "operator");
            String second = (String) Jsons.parseObject(fresh.body()).get("taskId");
            String many = IntStream.range(0, 40).mapToObj(i -> "seed-" + i).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(world.postAs("/api/v1/app-checks/tasks/" + second + "/seeds:import", many, "text/plain", "1001", "operator").statusCode()).isEqualTo(200);
            var next = world.confirmation(second, "1001", "operator");
            String nextBody = Jsons.toJson(Map.of("expectedItemCount", 4, "credits", next));
            assertThat(world.postAs("/api/v1/tasks/" + second + "/approve", nextBody, "application/json", "1001", "operator").statusCode()).isEqualTo(409);
            world.fund("1001", 10);
            assertThat(world.postAs("/api/v1/tasks/" + second + "/approve", nextBody, "application/json", "1001", "operator").statusCode()).isEqualTo(409);
            assertThat(world.approveAs(second, 4, "1001", "operator").statusCode()).isEqualTo(200);
            assertThat(world.get("/api/v1/users/1001/credits")).containsEntry("balance", 7L);
            world.context.getBean(com.xa.mass.server.task.retention.TaskRetentionService.class).maintain(id, System.currentTimeMillis() + Duration.ofDays(8).toMillis());
            assertThat(world.get("/api/v1/users/1001/credits")).containsEntry("balance", 7L);
            var entries = (List<Map<String,Object>>) world.get("/api/v1/users/1001/credits/transactions").get("entries");
            assertThat(entries).filteredOn(e -> e.get("business") instanceof Map<?,?> b && id.equals(b.get("taskId"))).singleElement()
                    .satisfies(e -> assertThat(e).containsEntry("actorUserId", "proof").containsEntry("delta", -23L));
        }
    }

    @Test @Timeout(150) @SuppressWarnings("unchecked")
    void groupedMd5ExecutesTwentyThreeSeedsAsThreeOrTwentyThreeItemsAcrossConfigurationRestart() throws Exception {
        try (var world = new World()) {
            var seeds = IntStream.range(0, 23).mapToObj(i -> "batch-seed-" + i).toList();
            String input = String.join("\n", seeds);
            var request = Map.of("requestId", "grouped-md5", "checkId", "md5-prefix");
            String old = (String) Jsons.parseObject(world.post("/api/v1/app-checks/tasks", Jsons.toJson(request), "application/json").body()).get("taskId");
            String oldRoute = "/api/v1/app-checks/tasks/" + old + "/seeds:import";
            assertThat(Jsons.parseObject(world.post(oldRoute, input, "text/plain").body()))
                    .containsEntry("uniqueCount", 23L).containsEntry("itemCount", 3L).containsEntry("addedCount", 3L);
            var saved = (Map<String,Object>) world.get("/api/v1/app-checks/configuration").get("saved");
            var config = (Map<String,Object>) saved.get("configuration");
            var execution = (Map<String,Object>) ((Map<String,Map<String,Object>>) config.get("checks")).get("md5-prefix").get("execution");
            execution.put("seedsPerItem", 1);
            assertThat(world.putConfiguration(Map.of("expectedFingerprint", saved.get("fingerprint"), "configuration", config)).statusCode()).isEqualTo(200);
            assertThat(task(world.detail(old))).containsEntry("seedsPerItem", "10").containsEntry("inputVersion", "5");
            world.restart();
            assertThat(Jsons.parseObject(world.post("/api/v1/app-checks/tasks", Jsons.toJson(request), "application/json").body())).containsEntry("taskId", old);
            assertThat(Jsons.parseObject(world.post(oldRoute, input, "text/plain").body())).containsEntry("existingCount", 3L).containsEntry("addedCount", 0L);
            String fresh = (String) Jsons.parseObject(world.post("/api/v1/app-checks/tasks", Jsons.toJson(Map.of(
                    "requestId", "single-md5", "checkId", "md5-prefix")), "application/json").body()).get("taskId");
            assertThat(Jsons.parseObject(world.post("/api/v1/app-checks/tasks/" + fresh + "/seeds:import", input, "text/plain").body()))
                    .containsEntry("uniqueCount", 23L).containsEntry("itemCount", 23L).containsEntry("addedCount", 23L);
            var root = java.nio.file.Files.createTempDirectory(Path.of("build"), "batch-worker-");
            var hostConfig = root.resolve("host.json");
            java.nio.file.Files.writeString(hostConfig, Jsons.toJson(Map.of("runtimeApiBaseUrl", world.base.toString(),
                    "sandboxRoot", root.resolve("data/scenario-workers").toString(), "controlPort", 0,
                    "workerGroups", Map.of("demo-sim", Map.of("count", 2, "events", List.of("extension.worker.string.md5.batch"),
                            "propertiesTemplate", Map.of("country", "CN"), "newEnvironment", false)))));
            var failure = new AtomicReference<Throwable>();
            var host = new Thread(() -> { try { com.xa.mass.workersimulator.WorkerSimulatorMain.main(new String[]{"--config", hostConfig.toString()}); }
                catch (Throwable error) { failure.set(error); } }, "batch-md5-proof");
            host.start();
            try {
                for (var test : Map.of(old, 10, fresh, 1).entrySet()) {
                    String id = test.getKey(); int size = test.getValue(), count = (23 + size - 1) / size;
                    assertThat(world.approve(id, count).statusCode()).isEqualTo(200);
                    var detail = world.settled(id, count);
                    assertThat(rows(detail)).hasSize(23);
                    var expectedGroups = new LinkedHashMap<String,List<String>>();
                    for (int offset = 0; offset < seeds.size(); offset += size) {
                        var group = seeds.subList(offset, Math.min(seeds.size(), offset + size));
                        expectedGroups.put(groupIdentity(group, size), group);
                    }
                    var ids = List.copyOf(expectedGroups.keySet());
                    var items = world.context.getBean(com.xa.mass.server.task.TaskDataService.class).loadTaskItems(CALLER, id, ids);
                    var raw = Jsons.parseObject(world.post("/api/v1/tasks/" + id + "/results:load", Jsons.toJson(ids), "application/json").body());
                    var states = Jsons.parseObject(world.post("/api/v1/tasks/" + id + "/items:states", Jsons.toJson(ids), "application/json").body());
                    assertThat(items.keySet()).containsExactlyInAnyOrderElementsOf(ids);
                    for (String message : ids) {
                        assertThat(items.get(message).payload()).containsEntry("values", expectedGroups.get(message));
                        assertThat((Map<String,Object>) states.get(message)).containsEntry("band", "terminal");
                        var stored = (Map<String,Object>) raw.get(message);
                        assertThat(stored).containsEntry("status", "succeeded");
                        var answers = (List<Map<String,Object>>) Jsons.parseObject((String) stored.get("opaqueResultPayload")).get("results");
                        assertThat(answers.stream().map(row -> row.get("input")).toList()).isEqualTo(expectedGroups.get(message));
                        for (var answer : answers) {
                            String seed = (String) answer.get("input");
                            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8)));
                            assertThat(answer).containsEntry("valid", true).containsEntry("md5", digest);
                            assertThat(rows(detail)).filteredOn(row -> message.equals(row.get("messageId")) && seed.equals(row.get("seed")))
                                    .singleElement().satisfies(row -> { assertThat(row).containsEntry("matched", digest.startsWith("0"));
                                        assertThat((Map<String,Object>) row.get("details")).containsEntry("md5", digest); });
                        }
                    }
                    var csv = world.post("/api/v1/app-checks/tasks/" + id + "/results:export", "", "application/json");
                    assertThat(csv.statusCode()).isEqualTo(200); assertThat(csv.headers().firstValue("X-Export-Count")).contains("23");
                    assertThat(csv.body().lines().count()).isEqualTo(24);
                }
                assertThat(failure.get()).isNull();
            } finally { host.interrupt(); host.join(15_000); assertThat(host.isAlive()).isFalse(); }
        }
    }
    static String groupIdentity(List<String> seeds, int size) throws Exception {
        var parts = new ArrayList<String>();
        if (size == 1) parts.add("app-checks/v1/text");
        else { parts.add("app-checks/v1/seed-group"); parts.add("text"); }
        parts.addAll(seeds);
        var bytes = new ByteArrayOutputStream();
        try (var tuple = new DataOutputStream(bytes)) {
            for (String part : parts) { byte[] encoded = part.getBytes(StandardCharsets.UTF_8); tuple.writeInt(encoded.length); tuple.write(encoded); }
        }
        return (size == 1 ? "seed-" : "group-") + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    @Test @Timeout(150) @SuppressWarnings("unchecked")
    void textSeedsUseTheExistingMd5WorkerWithoutPhoneOptionsAndRetainTheirSnapshot() throws Exception {
        try (var world = new World()) {
            var view = world.get("/api/v1/app-checks/configuration");
            var saved = (Map<String,Object>) view.get("saved");
            var config = (Map<String,Object>) saved.get("configuration");
            var checks = (Map<String,Object>) config.get("checks");
            checks.put("text-proof", Map.of("unitPrice", 1, "label", "Text proof", "checkType", "md5-prefix-demo-v1", "workerGroupId", "demo-sim", "parameters", Map.of(),
                    "execution", Map.of("eventCode", "extension.worker.string.md5",
                            "refill", List.of(Map.of("poolName", "any", "target", Map.of(), "count", 100)),
                            "workerSelector", Map.of("executorName", "worker.any", "input", Map.of()))));
            assertThat(world.putConfiguration(Map.of("expectedFingerprint", saved.get("fingerprint"), "configuration", config)).statusCode()).isEqualTo(200);
            assertThat(world.get("/api/v1/app-checks/catalog").get("checks").toString()).doesNotContain("text-proof");
            world.restart();
            assertThat(world.get("/api/v1/app-checks/catalog").get("checks").toString()).contains("text-proof");

            var request = new LinkedHashMap<String,Object>(Map.of("requestId", "text-seeds", "checkId", "text-proof"));
            request.put("country", "CN");
            assertThat(world.post("/api/v1/app-checks/tasks", Jsons.toJson(request), "application/json").statusCode()).isEqualTo(400);
            request.remove("country");
            var created = world.post("/api/v1/app-checks/tasks", Jsons.toJson(request), "application/json");
            assertThat(created.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(created.body()).get("taskId");
            String text = "\uFEFF a \r\nabc\n中文\nA\n\ta\t\n\n";
            String route = "/api/v1/app-checks/tasks/" + id + "/seeds:import";
            var importResponse = world.post(route, text, "text/plain");
            assertThat(importResponse.statusCode()).as(importResponse.body()).isEqualTo(200);
            var imported = Jsons.parseObject(importResponse.body());
            assertThat(imported).containsEntry("addedCount", 4L).containsEntry("duplicateCount", 1L).containsEntry("emptyCount", 1L);
            assertThat(Jsons.parseObject(world.post(route, text, "text/plain").body())).containsEntry("addedCount", 0L).containsEntry("existingCount", 4L);
            assertThat(task(world.detail(id))).containsEntry("seedType", "text").doesNotContainKey("country");

            String primeId = (String) Jsons.parseObject(world.post("/api/v1/app-checks/tasks",
                    Jsons.toJson(Map.of("requestId", "prime-seeds", "checkId", "uint32-prime")), "application/json").body()).get("taskId");
            String primeRoute = "/api/v1/app-checks/tasks/" + primeId + "/seeds:import";
            String invalid = IntStream.range(0, 201).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining("\n")) + "\n4294967296";
            var rejected = world.post(primeRoute, invalid, "text/plain");
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).contains("202");
            assertThat(task(world.detail(primeId))).containsEntry("totalCount", 0L);
            var primeSeeds = Map.of("0", false, "1", false, "2", true, "17", true, "017", true, "49", false,
                    "4294967291", true, "4294967295", false);
            String primeFile = "\uFEFF " + String.join("\n", primeSeeds.keySet()) + "\n\t17\t\n\n";
            var primeReceipt = world.post(primeRoute, primeFile, "text/plain");
            assertThat(primeReceipt.statusCode()).as(primeReceipt.body()).isEqualTo(200);
            assertThat(Jsons.parseObject(primeReceipt.body())).containsEntry("addedCount", 8L).containsEntry("duplicateCount", 1L);
            assertThat(Jsons.parseObject(world.post(primeRoute, primeFile, "text/plain").body())).containsEntry("addedCount", 0L).containsEntry("existingCount", 8L);
            assertThat(world.approve(primeId, 8).statusCode()).isEqualTo(200);
            Path root = java.nio.file.Files.createTempDirectory(Path.of("build"), "text-worker-");
            Path hostConfig = root.resolve("host.json");
            java.nio.file.Files.writeString(hostConfig, Jsons.toJson(Map.of("runtimeApiBaseUrl", world.base.toString(),
                    "sandboxRoot", root.resolve("data/scenario-workers").toString(), "controlPort", 0,
                    "workerGroups", Map.of("demo-sim", Map.of("count", 2, "events", List.of("extension.worker.string.md5", "extension.worker.integer.uint32-prime"),
                            "propertiesTemplate", Map.of("country", "CN"), "newEnvironment", false)))));
            var hostFailure = new AtomicReference<Throwable>();
            var host = new Thread(() -> {
                try { com.xa.mass.workersimulator.WorkerSimulatorMain.main(new String[]{"--config", hostConfig.toString()}); }
                catch (Throwable failure) { hostFailure.set(failure); }
            }, "text-proof-host");
            host.start();
            try {
                assertThat(world.approve(id, 4).statusCode()).isEqualTo(200);
                var detail = world.settled(id, 4);
                // Fixed independent vectors, not expected values generated from the Scenario verdict.
                var expected = Map.of("a", "0cc175b9c0f1b6a831c399e269772661", "abc", "900150983cd24fb0d6963f7d28e17f72",
                        "中文", "a7bac2239fcdcb3a067903d8077c4a07", "A", "7fc56270e7a70fa81a5935b72eacbe29");
                assertThat(rows(detail)).hasSize(4).allSatisfy(row -> {
                    String seed = (String) row.get("seed");
                    assertThat((Map<String,Object>) row.get("details")).containsEntry("md5", expected.get(seed));
                    assertThat(row).containsEntry("matched", seed.equals("a")).doesNotContainKeys("number", "contentError");
                });
                var ids = rows(detail).stream().map(row -> (String) row.get("messageId")).toList();
                var raw = Jsons.parseObject(world.post("/api/v1/tasks/" + id + "/results:load", Jsons.toJson(ids), "application/json").body());
                assertThat(raw).hasSize(4);
                var items = world.context.getBean(com.xa.mass.server.task.TaskDataService.class).loadTaskItems(CALLER, id, ids);
                var states = Jsons.parseObject(world.post("/api/v1/tasks/" + id + "/items:states", Jsons.toJson(ids), "application/json").body());
                assertThat(items).hasSize(4);
                for (String messageId : ids) {
                    String seed = (String) items.get(messageId).payload().get("value");
                    var stored = (Map<String,Object>) raw.get(messageId);
                    assertThat(stored).containsEntry("status", "succeeded");
                    assertThat(Jsons.parseObject((String) stored.get("opaqueResultPayload")))
                            .containsEntry("input", seed).containsEntry("md5", expected.get(seed)).containsEntry("valid", true);
                    assertThat((Map<String,Object>) states.get(messageId)).containsEntry("band", "terminal");
                }
                for (String filter : List.of("all", "matched", "unmatched")) {
                    var csv = world.post("/api/v1/app-checks/tasks/" + id + "/results:export?filter=" + filter, "", "application/json");
                    assertThat(csv.statusCode()).isEqualTo(200);
                    assertThat(csv.headers().firstValue("X-Export-Count")).contains(filter.equals("all") ? "4" : filter.equals("matched") ? "1" : "3");
                    assertThat(csv.body()).contains("种子", "摘要").doesNotContain("地区");
                }
                var primeDetail = world.settled(primeId, 8);
                assertThat(rows(primeDetail)).hasSize(8).allSatisfy(row -> {
                    String seed = (String) row.get("seed");
                    assertThat(row.get("matched")).isEqualTo(primeSeeds.get(seed));
                    assertThat((Map<String,Object>) row.get("details")).containsEntry("isPrime", primeSeeds.get(seed));
                });
                var primeIds = rows(primeDetail).stream().map(row -> (String) row.get("messageId")).toList();
                var primeRaw = Jsons.parseObject(world.post("/api/v1/tasks/" + primeId + "/results:load", Jsons.toJson(primeIds), "application/json").body());
                var primeStates = Jsons.parseObject(world.post("/api/v1/tasks/" + primeId + "/items:states", Jsons.toJson(primeIds), "application/json").body());
                for (var rawValue : primeRaw.values()) {
                    var stored = (Map<String,Object>) rawValue;
                    var content = Jsons.parseObject((String) stored.get("opaqueResultPayload"));
                    assertThat(stored).containsEntry("status", "succeeded");
                    assertThat(content).containsEntry("valid", true).containsEntry("isPrime", primeSeeds.get(content.get("input")));
                }
                assertThat(primeStates).hasSize(8).allSatisfy((message, value) -> assertThat((Map<String,Object>) value).containsEntry("band", "terminal"));
                assertThat(hostFailure.get()).isNull();
                world.restart();
                assertThat(world.detail(id)).isEqualTo(detail);
                assertThat(Jsons.parseObject(world.post("/api/v1/app-checks/tasks", Jsons.toJson(request), "application/json").body())).containsEntry("taskId", id);
                var savedCsv = new HashMap<String,String>();
                for (String taskId : List.of(id, primeId))
                    savedCsv.put(taskId, world.post("/api/v1/app-checks/tasks/" + taskId + "/results:export", "", "application/json").body());
                Path archives = root.resolve("archives");
                world.restart("--xa.mass.task-retention.enabled=true", "--xa.mass.task-retention.grace=0ms",
                        "--xa.mass.task-retention.directory=" + archives.toAbsolutePath());
                for (String taskId : List.of(id, primeId)) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    Object phase;
                    do {
                        phase = ((Map<?,?>) world.get("/api/v1/projects/app-checks/tasks/" + taskId).get("retention")).get("phase");
                        if ("RELEASED".equals(phase)) break;
                        Thread.sleep(20);
                    } while (System.nanoTime() < deadline);
                    assertThat(phase).isEqualTo("RELEASED");
                    assertThat(rows(world.detail(taskId))).containsExactlyInAnyOrderElementsOf(rows(taskId.equals(id) ? detail : primeDetail));
                    assertThat(world.post("/api/v1/app-checks/tasks/" + taskId + "/results:export", "", "application/json").body().lines().toList())
                            .containsExactlyInAnyOrderElementsOf(savedCsv.get(taskId).lines().toList());
                }
            } finally {
                host.interrupt(); host.join(15_000);
                assertThat(host.isAlive()).isFalse();
            }
        }
    }
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    @Test @Timeout(120)
    @SuppressWarnings("unchecked")
    void persistedConfigurationAddsAnApplicationOnlyAfterRestartAndNeverEntersExecutionReads() throws Exception {
        try (var world = new World()) {
            var initial = world.get("/api/v1/app-checks/configuration");
            var saved = (Map<String, Object>) initial.get("saved");
            var config = (Map<String, Object>) saved.get("configuration");
            ((Map<String, Object>) config.get("checks")).put("app-c", Map.of("unitPrice", 1, "label", "App C", "workerGroupId", "app-c-sim",
                    "checkType", "registration-v1", "execution", Map.of("eventCode", "extension.worker.catalog.lookup",
                            "refill", List.of(Map.of("poolName", "any", "target", Map.of(), "count", 100)),
                            "workerSelector", Map.of("executorName", "worker.any", "input", Map.of()))));
            var request = Map.of("expectedFingerprint", saved.get("fingerprint"), "configuration", config);
            var response = world.putConfiguration(request);
            assertThat(response.statusCode()).isEqualTo(200);
            String updatedFingerprint = (String) ((Map<?, ?>) Jsons.parseObject(response.body()).get("saved")).get("fingerprint");
            assertThat(updatedFingerprint).matches("[0-9a-f]{64}").isNotEqualTo(saved.get("fingerprint"));
            assertThat(Jsons.parseObject(response.body())).containsEntry("restartRequired", true);
            assertThat(world.get("/api/v1/app-checks/catalog").get("checks").toString()).doesNotContain("app-c");
            assertThat(world.putConfiguration(request).statusCode()).isEqualTo(409);
            assertThat(world.putConfiguration(Map.of("expectedFingerprint", updatedFingerprint, "configuration", config, "unexpected", true)).statusCode()).isEqualTo(400);
            var noChange = world.putConfiguration(Map.of("expectedFingerprint", updatedFingerprint, "configuration", config));
            assertThat(((Map<?, ?>) Jsons.parseObject(noChange.body()).get("saved")).get("fingerprint")).isEqualTo(updatedFingerprint);
            assertThat(world.post("/api/v1/app-checks/tasks", Jsons.toJson(Map.of("requestId", "not-effective",
                    "checkId", "app-c", "country", "CN")), "application/json")
                    .statusCode()).isEqualTo(400);

            var groups = Jsons.parseObject(world.context.getEnvironment().getProperty("xa.mass.worker-assembly.group-config-json"));
            assertThatThrownBy(() -> world.restart(
                    "--xa.mass.worker-matching.groups.app-c-sim.pools[0]=any",
                    "--xa.mass.worker-matching.groups.app-c-sim.functions[0]=worker.any"))
                    .hasStackTraceContaining("project=app-checks group=app-c-sim Group is not registered");
            groups.put("app-c-sim", Map.of("attributes", Map.of(), "eventCodes", List.of("extension.worker.catalog.lookup")));
            world.restart("--xa.mass.worker-assembly.group-config-json=" + Jsons.toJson(groups));
            var active = world.get("/api/v1/app-checks/configuration");
            assertThat(active).containsEntry("restartRequired", false);
            assertThat(((Map<?, ?>) active.get("effective")).get("fingerprint")).isEqualTo(updatedFingerprint);
            assertThat(world.get("/api/v1/app-checks/catalog").get("checks").toString()).contains("app-c-sim", "App C");
            var bindings = world.context.getBean(com.xa.mass.server.worker.observation.WorkerGroupBindings.class);
            var projection = bindings.projections().getFirst();
            assertThat(projection.projectionsByGroup()).doesNotContainKey("app-c-sim");
            assertThat(bindings.definitions().getFirst().limitsByGroup()).doesNotContainKey("app-c-sim");
            // A real file failure affects configuration reads, not the already captured execution configuration.
            java.nio.file.Files.delete(Path.of(world.context.getEnvironment()
                    .getRequiredProperty("xa.mass.scenarios.app-checks.configuration.path")));
            assertThat(world.http.send(world.request("/api/v1/app-checks/configuration", "proof", "admin").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            try (var workers = world.registrationWorkers("app-c-sim")) {
                workers.start();
                var ids = world.readyFacts(workers, "app-c-sim");
                String taskId = world.create("app-c-real-path", "app-c", 3);
                var detail = world.settled(taskId, 3);
                assertThat(task(detail)).containsEntry("workerGroupId", "app-c-sim").containsEntry("succeededCount", 3L);
                assertThat(rows(detail)).hasSize(3).allSatisfy(row -> assertThat(row).containsEntry("matched", true));
                assertThat(rows(detail)).allSatisfy(row -> assertThat(row).doesNotContainKeys("simulatedDelayMillis", "contentError"));
                assertThat(world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-c-sim", ids).values())
                        .allSatisfy(facts -> assertThat(facts.platformProperties()).doesNotContainKeys("lastAssignedAt", "windowAssignmentCount"));
                assertThat(world.post("/api/v1/app-checks/tasks/" + taskId + "/results:export", "", "application/json")
                        .headers().firstValue("X-Export-Count")).contains("3");
            }
        }
    }

    @Test @Timeout(240)
    void observedWindowRejectsActualStockAndRecoversThroughNormalRecycling() throws Exception {
        try (var world = new World(1, 1); var worker = world.workers("app-a-sim")) {
            worker.start();
            List<String> ids = world.readyFacts(worker, "app-a-sim");
            // Leave room to witness a real rejected take in the original window, not a timing-only absence.
            while (60_000 - System.currentTimeMillis() % 60_000 < 20_000) Thread.sleep(100);
            String first = world.create("window-first", "app-a-registration", 1);
            world.settled(first, 1);
            world.assertAllocationProjection("app-a-sim", ids);
            var before = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids)
                    .get(ids.getFirst()).platformProperties();
            long firstWindow = ((Number) before.get("lastAssignedAt")).longValue() / 60_000;
            assertThat(((Number) before.get("windowAssignmentCount")).longValue()).isEqualTo(1);
            String second = world.create("window-second", "app-a-registration", 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (world.selections.rejectedAt.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            long rejectedAt = world.selections.rejectedAt.get();
            assertThat(rejectedAt).as("a nonempty offered batch was rejected during Pool refill; reads=%s takes=%s empty=%s",
                    world.selections.snapshots.get(), world.selections.takes.get(), world.selections.empty.get()).isPositive();
            assertThat(rejectedAt / 60_000).isEqualTo(firstWindow);
            assertThat(world.observations.snapshot()).hasSize(1);
            assertThat(task(world.detail(second))).containsEntry("activeCount", 1L);
            // No patch, direct acquisition, synthetic supply, Score write or retry mutation helps this request.
            var result = world.settled(second, 1, 180);
            assertThat(task(result)).containsEntry("succeededCount", 1L);
            world.assertAllocationProjection("app-a-sim", ids);
            assertThat(world.observations.snapshot()).hasSize(2);
            assertThat(world.observations.snapshot().getLast().observedAtMillis() / 60_000).isGreaterThan(firstWindow);
            assertThat(((Map<?,?>) rows(result).getFirst().get("details")).get("workerId")).isEqualTo(ids.getFirst());
            assertThat(world.selections.takeSnapshots.get()).isZero();
            assertThat(world.selections.refillSnapshots.get()).isPositive();
        }
    }

    /** Observes the production read and take; does not change its candidates, properties or decisions. */
    static final class WindowSelectionWitness implements BeanPostProcessor {
        final AtomicLong snapshots = new AtomicLong();
        final AtomicLong rejectedAt = new AtomicLong();
        final AtomicLong takes = new AtomicLong();
        final AtomicLong empty = new AtomicLong();
        final AtomicLong takeSnapshots = new AtomicLong(), refillSnapshots = new AtomicLong();
        final ThreadLocal<String> phase = new ThreadLocal<>();
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof RedisClient client) client.addListener(new CommandListener() {
                @Override public void commandStarted(CommandStartedEvent event) {
                    if (event.getCommand().getType().toString().equals("EVAL_RO")
                            && event.getCommand().getArgs().toCommandString().contains(":matching:worker:platform-properties:app-a-sim")) {
                        snapshots.incrementAndGet();
                        if ("take".equals(phase.get())) takeSnapshots.incrementAndGet();
                        if ("refill".equals(phase.get())) refillSnapshots.incrementAndGet();
                    }
                }
            });
            if (!(bean instanceof WorkerMatchingCatalog catalog)) return bean;
            var witnessed = spy(catalog);
            doAnswer(call -> {
                phase.set("take");
                try {
                    @SuppressWarnings("unchecked") var result = (Map<String, WorkerCandidate>) call.callRealMethod();
                    Map<String, WorkerQuery> inputs = call.getArgument(1);
                    if (!inputs.isEmpty()) { takes.incrementAndGet(); if (result.isEmpty()) empty.incrementAndGet(); }
                    return result;
                } finally { phase.remove(); }
            }).when(witnessed).take(anyString(), anyMap());
            doAnswer(call -> {
                phase.set("refill");
                try {
                    long before = refillSnapshots.get();
                    var outcome = (com.xa.mass.kernel.assignment.WorkerMatching.RefillOutcome) call.callRealMethod();
                    int accepted = outcome.admittedWorkerIds().size();
                    Map<String, Long> offered = call.getArgument(2);
                    if ("app-a-sim".equals(call.getArgument(0)) && !offered.isEmpty()
                            && accepted == 0 && refillSnapshots.get() > before)
                        rejectedAt.compareAndSet(0, System.currentTimeMillis());
                    return outcome;
                } finally { phase.remove(); }
            }).when(witnessed).refill(anyString(), anyList(), anyMap());
            return witnessed;
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1000,1000,0,false", "0,0,0,true", "1000,1000,6000,false"})
    @Timeout(90)
    void allocationProjectionPrecedesReportAndSurvivesSuccessFailureAndLateResults(
            int registered, int unregistered, int delay, boolean failure) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var world = new World(1000, 2, Map.of("app-a-registration", simulation(registered, unregistered, delay),
                "app-b-registration", simulation(0, 0, 0))); var workers = world.workers("app-a-sim", entered, release)) {
            workers.start();
            // Establish the independent first Facts publication before exercising this lossy projection.
            List<String> ids = world.readyFacts(workers, "app-a-sim");
            String task = world.create("allocation-observation", "app-a-registration", 1);
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(rows(world.detail(task)))
                        .noneMatch(row -> "succeeded".equals(row.get("resultStatus")) || "failed".equals(row.get("resultStatus")));
                world.assertAllocationProjection("app-a-sim", ids);
            } finally { release.countDown(); }
            var result = world.settled(task, 1);
            assertThat(task(result)).containsEntry(failure ? "failedCount" : "succeededCount", 1L);
            if (failure) assertThat(world.attempts).anyMatch(Attempt::failed);
            else assertThat(rows(result)).allSatisfy(row -> {
                assertThat(row).containsEntry("matched", true);
                assertThat(((Map<?,?>) row.get("details")).get("simulatedDelayMillis")).isEqualTo((long) delay);
            });
            world.assertAllocationProjection("app-a-sim", ids);
            var saved = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids);
            world.restart();
            var restored = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids);
            ids.forEach(id -> assertThat(restored.get(id).platformProperties()).isEqualTo(saved.get(id).platformProperties()));
        } finally { release.countDown(); }
    }

    @Test @Timeout(150)
    void realHandlersIsolateAppsThrowFailuresAndRetainResultsAcrossServerRestart() throws Exception {
        try (var world = new World(); var a = world.workers("app-a-sim"); var b = world.workers("app-b-sim")) {
            a.start(); b.start();
            world.readyFacts(a, "app-a-sim");
            world.readyFacts(b, "app-b-sim");
            String registered = world.create("registered", "app-a-registration", 101);
            String failed = world.create("failed", "app-b-registration", 2);
            var positive = world.settled(registered, 101);
            var failures = world.settled(failed, 2);
            world.configure(Map.of("app-a-registration", simulation(0, 1000, 0), "app-b-registration", simulation(500, 900, 10)));
            world.readyFacts(a, "app-a-sim"); world.readyFacts(b, "app-b-sim");
            var replay = world.post("/api/v1/app-checks/tasks", Jsons.toJson(Map.of("requestId","registered", "checkId","app-a-registration", "country","CN")), "application/json");
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(Jsons.parseObject(replay.body()).get("taskId")).isEqualTo(registered);
            assertThat(rows(world.detail(registered))).containsExactlyInAnyOrderElementsOf(rows(positive));
            String unregistered = world.create("unregistered", "app-a-registration", 4);
            String mixed = world.create("mixed", "app-b-registration", 12);
            var negative = world.settled(unregistered, 4);
            var mix = world.settled(mixed, 12);
            assertThat(task(positive)).containsEntry("succeededCount", 101L).containsEntry("failedCount", 0L);
            assertThat(rows(positive)).hasSizeLessThanOrEqualTo(100).isNotEmpty();
            assertThat(positive).containsEntry("resultsTruncated", true);
            assertThat(rows(positive)).allSatisfy(row -> {
                assertThat(row).containsEntry("matched", true);
                assertThat(((Map<?,?>) row.get("details")).get("workerGroupId")).isEqualTo("app-a-sim");
            });
            assertThat(task(negative)).containsEntry("succeededCount", 4L).containsEntry("failedCount", 0L);
            assertThat(rows(negative)).allSatisfy(row -> assertThat(row).containsEntry("matched", false)
                    .containsEntry("resultStatus", "succeeded"));
            assertThat(task(failures)).containsEntry("failedCount", 2L).containsEntry("succeededCount", 0L);
            assertThat(rows(failures)).allSatisfy(row -> {
                assertThat(row).containsEntry("resultStatus", "failed").doesNotContainKey("matched");
                assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(row.get("seed"))
                        && attempt.group().equals("app-b-sim") && attempt.failed());
            });
            for (var row : rows(mix)) {
                if (!"succeeded".equals(row.get("resultStatus"))) continue;
                assertThat(row).doesNotContainKey("contentError");
                assertThat(((Map<?,?>) row.get("details")).get("workerGroupId")).isEqualTo("app-b-sim");
                long bucket = hash("outcome", (String) ((Map<?,?>) row.get("details")).get("workerId"), (String) task(mix).get("salt"), (String) row.get("seed"));
                assertThat(bucket).isLessThan(900);
                assertThat(row.get("matched")).isEqualTo(bucket < 500);
            }
            // A delayed real Handler crosses the existing five-second claim. No single-execution assertion.
            world.configure(Map.of("app-a-registration", simulation(1000, 1000, 6000)));
            world.readyFacts(a, "app-a-sim");
            String delayed = world.create("delayed", "app-a-registration", 1);
            var late = world.settled(delayed, 1);
            assertThat(task(late)).containsEntry("succeededCount", 1L);
            assertThat(rows(late)).allSatisfy(row -> assertThat(((Map<?,?>) row.get("details")).get("simulatedDelayMillis")).isEqualTo(6000L));
            assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(rows(late).getFirst().get("seed"))
                    && !attempt.failed() && attempt.elapsedNanos() >= TimeUnit.SECONDS.toNanos(6));
            var csv = world.post("/api/v1/app-checks/tasks/" + registered + "/results:export?filter=matched", "", "application/json");
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.headers().firstValue("X-Export-Count")).contains("101");
            assertThat(csv.body().lines().count()).isEqualTo(102);
            var emptyCsv = world.post("/api/v1/app-checks/tasks/" + failed + "/results:export", "", "application/json");
            assertThat(emptyCsv.headers().firstValue("X-Export-Count")).contains("0");
            var original = task(negative);
            world.restart();
            var retained = world.detail(unregistered);
            assertThat(task(retained)).containsAllEntriesOf(original);
            assertThat(rows(retained)).containsExactlyInAnyOrderElementsOf(rows(negative));
            assertThat(world.get("/api/v1/projects/app-checks/tasks?limit=100").get("tasks")).isInstanceOf(List.class);
        }
    }

    @Test @Timeout(75)
    void userDirectoryRangesAreIndependentOfTheProjectBatchOverRealHttp() throws Exception {
        try (var world = new World()) {
            String route = "/api/v1/app-checks/tasks";
            String directory = "/api/v1/projects/app-checks/tasks";

            var body = new LinkedHashMap<String, Object>(Map.of("requestId", "personal", "checkId", "app-a-registration", "country", "CN"));
            var response = world.postAs(route, Jsons.toJson(body), "application/json", "1002", "operator");
            assertThat(response.statusCode()).isEqualTo(201);
            String target = (String) Jsons.parseObject(response.body()).get("taskId");
            for (int i = 0; i < 101; i++) {
                body.put("requestId", "other-" + i);
                assertThat(world.postAs(route, Jsons.toJson(body), "application/json", "1001", "operator").statusCode()).isEqualTo(201);
            }
            var project = world.get(directory);
            assertThat(project).containsEntry("truncated", true);
            assertThat((List<?>) project.get("tasks")).hasSize(100).allSatisfy(row ->
                    assertThat(((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) row).get("task")).get("metadata")).get("userId")).isEqualTo("1001"));
            var own = world.http.send(world.request(directory, "1002", "operator").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(own.statusCode()).isEqualTo(200);
            var parsed = Jsons.parseObject(own.body());
            assertThat(parsed).containsEntry("truncated", false);
            assertThat((List<?>) parsed.get("tasks")).singleElement().satisfies(row ->
                    assertThat(((Map<?, ?>) row).get("taskId")).isEqualTo(target));
            assertThat(world.get(directory + "?userId=1002")).isEqualTo(parsed);
            assertThat(world.http.send(world.request(directory + "?userId=1001", "1002", "operator").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(world.http.send(world.request(directory + "?userId=0001", "1002", "operator").build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            assertThat(task(world.detail(target))).containsEntry("userId", "1002");
        }
    }

    @Test @Timeout(75)
    void directoryCallersRestrictScenarioOperationsAndRetainOwnershipAfterRestart() throws Exception {
        try (var world = new World()) {
            String route = "/api/v1/app-checks/tasks";
            String directory = "/api/v1/projects/app-checks/tasks";
            assertThat(world.http.send(HttpRequest.newBuilder(world.base.resolve(directory)).build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(400);
            assertThat(world.http.send(HttpRequest.newBuilder(world.base.resolve(directory)).header("X-XA-Dev-User", "_w")
                    .header("X-XA-Dev-Role", "operator").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);

            var body = new LinkedHashMap<String, Object>(Map.of("requestId", "callerb", "checkId", "app-a-registration", "country", "CN"));
            String a = "testa", b = "testb";
            var response = world.postAs(route, Jsons.toJson(body), "application/json", a, "operator");
            assertThat(response.statusCode()).isEqualTo(201);
            String first = (String) Jsons.parseObject(response.body()).get("taskId");
            body.put("requestId", "caller-b");
            response = world.postAs(route, Jsons.toJson(body), "application/json", b, "operator");
            assertThat(response.statusCode()).isEqualTo(201);
            String second = (String) Jsons.parseObject(response.body()).get("taskId");
            for (String user : List.of(a, b)) {
                var listing = world.http.send(world.request(directory, user, "operator").build(), HttpResponse.BodyHandlers.ofString());
                assertThat(listing.statusCode()).isEqualTo(200);
                assertThat((List<?>) Jsons.parseObject(listing.body()).get("tasks")).singleElement()
                        .satisfies(row -> assertThat(((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) row).get("task")).get("metadata")).get("userId")).isEqualTo(user));
            }
            assertThat((List<?>) world.get(directory).get("tasks")).hasSize(2);
            assertThat(world.http.send(world.request(directory + "/" + first, b, "operator").build(), HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(403);
            for (String suffix : List.of("/seeds:import", "/results:export"))
                assertThat(world.postAs(route + "/" + first + suffix, "1", suffix.equals("/seeds:import") ? "text/plain" : "application/json", b, "operator")
                        .statusCode()).isEqualTo(403);
            for (String suffix : List.of("/approve", "/close", "/directory:ensure", "/results:export"))
                assertThat(world.postAs("/api/v1/tasks/" + first + suffix, "{\"expectedItemCount\":1}", "application/json", b, "operator").statusCode()).isEqualTo(403);
            for (String suffix : List.of("/results:load", "/items:states"))
                assertThat(world.postAs("/api/v1/tasks/" + first + suffix, "[\"m\"]", "application/json", b, "operator").statusCode()).isEqualTo(403);
            assertThat(world.postAs(directory + ":stats", Jsons.toJson(List.of(first, second)), "application/json", b, "operator").statusCode()).isEqualTo(403);
            assertThat(world.http.send(world.request(route + "/" + first + "/results", b, "operator").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(task(world.detail(first))).containsEntry("totalCount", 0L).containsEntry("state", "pre_review");
            String config = "/api/v1/app-checks/configuration";
            assertThat(world.http.send(world.request(config, a, "operator").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(world.http.send(world.request(config, a, "operator").header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            world.restart();
            assertThat(task(world.detail(first))).containsEntry("userId", a);
            assertThat(world.postAs(route + "/" + first + "/seeds:import", "8613800000001", "text/plain", a, "operator").statusCode()).isEqualTo(200);
            assertThat(world.approveAs(first, 1, a, "operator").statusCode()).isEqualTo(200);
            assertThat(world.postAs("/api/v1/tasks/" + first + "/close", "", "application/json", a, "operator").statusCode()).isEqualTo(200);
            assertThat(world.postAs(route + "/" + first + "/results:export", "", "application/json", a, "operator").statusCode()).isEqualTo(200);
            assertThat(world.post("/api/v1/tasks/" + second + "/close", "", "application/json").statusCode()).isEqualTo(200);
        }
    }

    @Test @Timeout(180)
    void directoryMutationsGovernBothTaskAndScenarioHttpWithoutRewritingAttribution() throws Exception {
        try (var world = new World()) {
            var inputs = Map.of(
                    "app-checks", Map.of("requestId", "access-check", "checkId", "app-a-registration", "country", "CN"),
                    "messages", Map.of("requestId", "access-message", "appId", "demo", "name", "Access", "recipientCountry", "CN", "body", "{}"));
            for (var entry : inputs.entrySet()) {
                String project = entry.getKey(), route = "/api/v1/" + project + "/tasks", directory = "/api/v1/projects/" + project + "/tasks";
                String body = Jsons.toJson(entry.getValue());
                var created = world.postAs(route, body, "application/json", "1001", "operator");
                assertThat(created.statusCode()).as(project).isEqualTo(201);
                String id = (String) Jsons.parseObject(created.body()).get("taskId");
                var original = world.context.getBean(com.xa.mass.kernel.task.TaskResourceCatalog.class)
                        .loadTaskAllocationDescriptors(List.of(id)).get(id);
                world.updateUser("1001", "operator", List.of());
                for (String path : List.of(directory, directory + "/" + id, route + "/" + id + "/results"))
                    assertThat(world.http.send(world.request(path, "1001", "operator").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
                assertThat(world.postAs(route, body, "application/json", "1001", "operator").statusCode()).isEqualTo(403);
                String suffix = project.equals("messages") ? "/recipients:import" : "/seeds:import";
                assertThat(world.postAs(route + "/" + id + suffix, "invalid input", "text/plain", "1001", "operator").statusCode()).isEqualTo(403);
                for (String action : List.of("/approve", "/close", "/directory:ensure", "/results:export"))
                    assertThat(world.postAs("/api/v1/tasks/" + id + action, "{\"expectedItemCount\":1}", "application/json", "1001", "operator").statusCode()).isEqualTo(403);
                for (String action : List.of("/items:states", "/results:load"))
                    assertThat(world.postAs("/api/v1/tasks/" + id + action, "[\"m\"]", "application/json", "1001", "operator").statusCode()).isEqualTo(403);
                assertThat(world.postAs(directory + ":stats", Jsons.toJson(List.of(id)), "application/json", "1001", "operator").statusCode()).isEqualTo(403);
                world.updateUser("1001", "admin", List.of());
                assertThat(world.postAs(route, body, "application/json", "1001", "operator").statusCode()).isEqualTo(403);
                assertThat(Jsons.parseObject(world.postAs(route, body, "application/json", "1001", "admin").body())).containsEntry("taskId", id);
                world.updateUser("1001", "operator", List.of("messages", "app-checks"));
                assertThat(world.postAs(route, body, "application/json", "1001", "admin").statusCode()).isEqualTo(403);
                assertThat(Jsons.parseObject(world.postAs(route, body, "application/json", "1001", "operator").body())).containsEntry("taskId", id);
                assertThat(world.context.getBean(com.xa.mass.kernel.task.TaskResourceCatalog.class).loadTaskAllocationDescriptors(List.of(id)).get(id)).isEqualTo(original);
                assertThat(world.http.send(world.request(directory, "missing", "admin").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            }
        }
    }

    @Test @Timeout(180)
    void emptyCreationLargeImportIdempotencyAndManualClosureUseRealOwners() throws Exception {
        try (var world = new World()) {
            String path = "/api/v1/app-checks/tasks";
            var body = Map.of("requestId", "large-import", "checkId", "app-a-registration", "country", "CN");
            String json = Jsons.toJson(body);
            var created = world.post(path, json, "application/json");
            assertThat(created.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(created.body()).get("taskId");
            String target = path + "/" + id;
            assertThat(task(world.detail(id))).containsEntry("userId", "proof");
            assertThat(world.postAs(path, json, "application/json", "another1", "admin").statusCode()).isEqualTo(201);
            var bodyCreator = new LinkedHashMap<>(body); bodyCreator.put("userId", "proof");
            assertThat(world.post(path, Jsons.toJson(bodyCreator), "application/json").statusCode()).isEqualTo(400);
            assertThat(Jsons.parseObject(world.postAs(path, json, "application/json", "  proof  ", "admin").body()))
                    .containsEntry("taskId", id);
            assertThat(task(world.detail(id))).containsEntry("state", "pre_review").containsEntry("totalCount", 0L);
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", "{\"expectedItemCount\":0}", "application/json").statusCode()).isEqualTo(400);
            assertThat(world.post(target + "/seeds:import", "86123\nwrong", "text/plain").statusCode()).isEqualTo(400);
            assertThat(task(world.detail(id))).containsEntry("totalCount", 0L);
            String numbers = IntStream.range(0, 100000).mapToObj(i -> "86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n"));
            var imported = world.post(target + "/seeds:import", numbers, "text/plain");
            assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
            assertThat(Jsons.parseObject(imported.body())).containsEntry("addedCount", 100000L);
            var owner = world.context.getBean(com.xa.mass.kernel.task.TaskRuntime.class);
            // Discover only caller-known IDs through the App Checks import identity convention.
            String message = "number-" + numberIdentity("+8613800000000");
            var original = owner.loadTaskItems(id, List.of(message)).get(message);
            assertThat(original.expireAtMillis() - original.createdAtMillis()).isEqualTo(Duration.ofDays(365).toMillis());
            var duplicate = world.post(target + "/seeds:import", "+8613800000000\n8613800000000\n8613911111111", "text/plain");
            assertThat(Jsons.parseObject(duplicate.body())).containsEntry("addedCount", 1L).containsEntry("existingCount", 1L).containsEntry("duplicateCount", 1L);
            assertThat(owner.loadTaskItems(id, List.of(message)).get(message)).isEqualTo(original);
            world.restart();
            var repeated = world.post(path, json, "application/json");
            assertThat(repeated.statusCode()).isEqualTo(201);
            assertThat(Jsons.parseObject(repeated.body()).get("taskId")).isEqualTo(id);
            assertThat(task(world.detail(id))).containsEntry("totalCount", 100001L).containsEntry("state", "pre_review");
            assertThat(task(world.detail(id))).containsEntry("userId", "proof");
            assertThat(((List<?>) world.get("/api/v1/projects/app-checks/tasks?limit=100").get("tasks")).stream()
                    .map(value -> (Map<?, ?>) value).filter(row -> id.equals(row.get("taskId"))).toList())
                    .singleElement().satisfies(row -> assertThat(((Map<?, ?>) ((Map<?, ?>) row.get("task")).get("metadata")).get("userId")).isEqualTo("proof"));
            assertThat(world.post("/api/v1/tasks/" + id + "/approve", "{\"expectedItemCount\":100000}", "application/json").statusCode()).isEqualTo(400);
            assertThat(world.approve(id, 100001).statusCode()).isEqualTo(200);
            assertThat(world.post("/api/v1/tasks/" + id + "/close", "", "application/json").statusCode()).isEqualTo(200);
            assertThat(task(world.detail(id))).containsEntry("activeCount", 100001L).containsEntry("state", "terminal");
            assertThat(world.approve(id, 100001).statusCode()).isEqualTo(400);
        }
    }

    @Test @Timeout(120)
    void carrierProtocolVerdictsAndCsvSurviveFiniteArchivalThenRespectPurge() throws Exception {
        try (var world = new World()) {
            String id;
            Map<String,Object> before;
            String csv;
            // Protocol/error fixture. The source/ZIP runner separately executes the installed libphonenumber Handler.
            var builder = JavaWorkerManager.builder(world.base, "demo-sim", WorkerTransportType.WEBSOCKET);
            for (String replica : world.replicas) builder.replica(replica, Map::of, List.of(
                    WorkerEventDefinition.extension("phonenumber.original-carrier.batch", WorkerEventParameterResolvers.jsonMap(), input -> {
                        var answers = new ArrayList<Map<String,Object>>();
                        for (Object value : (List<?>) input.get("rawNumbers")) {
                            String number = (String) value;
                            int kind = Character.digit(number.charAt(number.length() - 1), 10);
                            var result = new LinkedHashMap<String,Object>(Map.of("input", number, "possible", true, "valid", kind != 2));
                            if (kind == 2) result.put("error", "INVALID_PHONE_NUMBER");
                            else if (kind != 3) result.put("originalCarrier", kind == 0 ? "Fixture Carrier" : "");
                            answers.add(result);
                        }
                        return Jsons.toJson(Map.of("results", answers));
                    })));
            try (var workers = builder.build()) {
                workers.start(); world.readyFacts(workers, "demo-sim");
                id = (String) Jsons.parseObject(world.post("/api/v1/app-checks/tasks", Jsons.toJson(Map.of(
                        "requestId", "carrier-retention", "checkId", "original-carrier", "country", "CN")), "application/json").body()).get("taskId");
                for (int kind = 0; kind < 4; kind++) {
                    var imported = world.post("/api/v1/app-checks/tasks/" + id + "/seeds:import",
                            "+861380000000" + kind + "\n+861380000001" + kind, "text/plain");
                    assertThat(imported.statusCode()).isEqualTo(200);
                    assertThat(Jsons.parseObject(imported.body())).containsEntry("uniqueCount", 2L).containsEntry("itemCount", 1L);
                }
                assertThat(world.approve(id, 4).statusCode()).isEqualTo(200);
                before = world.settled(id, 4);
                assertThat(task(before)).containsEntry("succeededCount", 4L).containsEntry("failedCount", 0L);
                assertThat(rows(before)).filteredOn(row -> Boolean.TRUE.equals(row.get("matched"))).hasSize(2);
                assertThat(rows(before)).filteredOn(row -> Boolean.FALSE.equals(row.get("matched"))).hasSize(4);
                assertThat(rows(before)).filteredOn(row -> row.containsKey("contentError")).hasSize(2)
                        .allSatisfy(row -> assertThat(row).doesNotContainKey("matched"));
                assertThat(rows(before)).allSatisfy(row -> assertThat(row).doesNotContainKeys("registered", "workerId", "workerGroupId"));
                var exported = world.post("/api/v1/app-checks/tasks/" + id + "/results:export", "", "application/json");
                assertThat(exported.statusCode()).isEqualTo(200);
                assertThat(exported.headers().firstValue("X-Export-Count")).contains("6");
                csv = exported.body();
                assertThat(csv).contains("原始运营商", "Fixture Carrier", "INVALID_PHONE_NUMBER", "NO_CARRIER_MAPPING");
                assertThat(world.post("/api/v1/app-checks/tasks/" + id + "/results:export?filter=registered", "", "application/json").statusCode()).isEqualTo(400);
            }
            var archives = java.nio.file.Files.createTempDirectory(Path.of("build"), "carrier-archives-");
            world.restart("--xa.mass.task-retention.enabled=true", "--xa.mass.task-retention.grace=0ms",
                    "--xa.mass.task-retention.directory=" + archives.toAbsolutePath());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            Object phase;
            do {
                var entry = world.get("/api/v1/projects/app-checks/tasks/" + id);
                phase = ((Map<?,?>) entry.get("retention")).get("phase");
                if ("RELEASED".equals(phase)) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertThat(phase).isEqualTo("RELEASED");
            assertThat(rows(world.detail(id))).containsExactlyInAnyOrderElementsOf(rows(before));
            assertThat(world.post("/api/v1/app-checks/tasks/" + id + "/results:export", "", "application/json").body().lines().toList())
                    .containsExactlyInAnyOrderElementsOf(csv.lines().toList());
            assertThat(world.post("/api/v1/tasks/" + id + "/results:load", "[\"known\"]", "application/json").statusCode()).isEqualTo(400);
            var purged = world.context.getBean(com.xa.mass.server.task.retention.TaskRetentionService.class)
                    .maintain(id, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(8));
            assertThat(purged.retention().phase()).isEqualTo("PURGED");
            var missing = world.http.send(world.request("/api/v1/app-checks/tasks/" + id + "/results", "proof", "admin").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(400);
            assertThat(Jsons.parseObject(missing.body()).get("code")).isEqualTo((long) com.xa.mass.server.error.ServerErrorCode.TASK_NOT_FOUND.code());
        }
    }

    static String numberIdentity(String number) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tuple = new DataOutputStream(bytes)) {
            for (String value : List.of("app-checks/v2/number", number)) { byte[] encoded = value.getBytes(StandardCharsets.UTF_8); tuple.writeInt(encoded.length); tuple.write(encoded); }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    @SuppressWarnings("unchecked") static Map<String, Object> task(Map<String, Object> detail) { return (Map<String, Object>) detail.get("task"); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> rows(Map<String, Object> detail) { return (List<Map<String, Object>>) detail.get("results"); }

    static long hash(String domain, String worker, String salt, String number) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tuple = new DataOutputStream(bytes)) {
            for (String field : List.of("app-checks/v1/" + domain, worker, salt, number)) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8); tuple.writeInt(value.length); tuple.write(value);
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        return new BigInteger(1, Arrays.copyOf(digest, 8)).mod(BigInteger.valueOf(1000)).longValueExact();
    }

    record Attempt(String group, String number, boolean failed, long elapsedNanos) {}

    static Map<String,Object> simulation(int registered, int unregistered, int delay) {
        return Map.of("ranges", Map.of("registered", List.of(0,registered), "unregistered", List.of(registered,unregistered),
                "failed", List.of(unregistered,1000)), "delayMs", List.of(delay,delay));
    }
    static final class World implements AutoCloseable {
        final String scope = "test_app_checks_" + UUID.randomUUID().toString().replace("-", "");
        final String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        final WorkerObservationWitness observations = new WorkerObservationWitness();
        final WindowSelectionWitness selections = new WindowSelectionWitness();
        final List<String> replicas;
        final URI base;
        final SpringApplication application = new SpringApplication(ServerBootConfiguration.class);
        String[] arguments;
        ConfigurableApplicationContext context;
        int sequence;
        final java.util.concurrent.atomic.AtomicBoolean loseDebitResponse = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean loseApprovalResponse = new java.util.concurrent.atomic.AtomicBoolean();
        void updateUser(String id, String role, List<String> projects) throws Exception {
            var response = http.send(request("/api/v1/users/" + id, "proof", "admin")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(Jsons.toJson(Map.of("displayName", "Updated user", "role", role, "projectIds", projects)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        }
        World() throws Exception {
            this(1000, 2);
        }
        World(long maxAssignments, int workerCount) throws Exception {
            this(maxAssignments, workerCount, Map.of("app-a-registration", simulation(1000,1000,0), "app-b-registration", simulation(0,0,0)));
        }
        World(long maxAssignments, int workerCount, Map<String,Object> parameters) throws Exception {
            replicas = IntStream.range(0, workerCount).mapToObj(i -> "replica-" + i).toList();
            int port = port(), adapter = port(); base = URI.create("http://127.0.0.1:" + port);
            application.setRegisterShutdownHook(false);
            application.addInitializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof com.xa.mass.server.credits.CreditAccountStore store) {
                        var observed = spy(store);
                        doAnswer(call -> { var result = call.callRealMethod();
                            if (loseDebitResponse.getAndSet(false)) throw new com.xa.mass.server.error.ServerException(com.xa.mass.server.error.ServerErrorCode.CREDIT_UNAVAILABLE, "proof.lostDebit", null, null);
                            return result; }).when(observed).debit(anyString(), anyString(), any());
                        return observed;
                    }
                    if (bean instanceof com.xa.mass.kernel.task.TaskLifecycleCommands commands) {
                        var observed = spy(commands);
                        doAnswer(call -> { var result = call.callRealMethod();
                            if (loseApprovalResponse.getAndSet(false)) throw new IllegalStateException("proof lost approval response");
                            return result; }).when(observed).approveTask(anyString());
                        return observed;
                    }
                    return bean;
                }
            }));
            application.addInitializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(observations));
            application.addInitializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(selections));
            arguments = new String[] { "--spring.profiles.active=preview", "--server.port=" + port,
                    "--xa.mass.users.seed=classpath:users/proof-directory.json", "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                    "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                    "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                    "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket",
                    "--logging.level.root=ERROR"};
            var argumentsWithStorage = new ArrayList<>(List.of(arguments));
            AppCheckConfigurationFixture.properties(maxAssignments, parameters).forEach((key,value) -> argumentsWithStorage.add("--" + key + "=" + value));
            arguments = argumentsWithStorage.toArray(String[]::new);
            context = application.run(arguments);
            var users = context.getBean(com.xa.mass.server.user.UserDirectory.class);
            for (var user : users.users()) fund(user.userId(), 1_000_000);
        }
        void fund(String userId, long amount) throws Exception {
            assertThat(post("/api/v1/users/" + userId + "/credits/adjustments", Jsons.toJson(Map.of("requestId", UUID.randomUUID().toString(),
                    "delta", amount, "reason", "App Checks proof fixture")), "application/json").statusCode()).isEqualTo(200);
        }
        Map<String, Object> confirmation(String id, String user, String role) throws Exception {
            var response = http.send(request("/api/v1/app-checks/tasks/" + id + "/credits", user, role).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var quote = Jsons.parseObject(response.body());
            return Map.of("requestId", UUID.randomUUID().toString(), "expectedSeedCount", quote.get("seedCount"), "expectedAmount", quote.get("amount"));
        }
        HttpResponse<String> approve(String id, int count) throws Exception { return approveAs(id, count, "proof", "admin"); }
        HttpResponse<String> approveAs(String id, int count, String user, String role) throws Exception {
            return postAs("/api/v1/tasks/" + id + "/approve", Jsons.toJson(Map.of("expectedItemCount", count, "credits", confirmation(id, user, role))), "application/json", user, role);
        }
        @SuppressWarnings("unchecked")
        void configure(Map<String,Object> parameters) throws Exception {
            var current = get("/api/v1/app-checks/configuration");
            var saved = (Map<String,Object>) current.get("saved");
            var config = (Map<String,Object>) saved.get("configuration");
            ((Map<String, Map<String,Object>>) config.get("checks")).forEach((checkId, app) -> {
                if (parameters.containsKey(checkId)) app.put("parameters", parameters.get(checkId));
            });
            assertThat(putConfiguration(Map.of("expectedFingerprint", saved.get("fingerprint"), "configuration", config)).statusCode()).isEqualTo(200);
            restart();
        }
        JavaWorkerManager registrationWorkers(String group) {
            var reference = new AtomicReference<JavaWorkerManager>();
            var builder = JavaWorkerManager.builder(base, group, WorkerTransportType.WEBSOCKET);
            for (String replica : replicas) {
                builder.replica(replica, () -> Map.of("simulated", "true"), List.of(
                        WorkerEventDefinition.extension("catalog.lookup", WorkerEventParameterResolvers.jsonMap(), input -> {
                            assertThat(input).containsOnlyKeys("number");
                            return Jsons.toJson(Map.of("number", input.get("number"), "registered", true,
                                    "workerId", reference.get().snapshot(replica).workerId(), "workerGroupId", group));
                        })));
            }
            var manager = builder.build(); reference.set(manager); return manager;
        }
        JavaWorkerManager workers(String group) {
            return workers(group, null, null);
        }
        JavaWorkerManager workers(String group, CountDownLatch entered, CountDownLatch release) {
            var reference = new AtomicReference<JavaWorkerManager>();
            var builder = JavaWorkerManager.builder(base, group, WorkerTransportType.WEBSOCKET);
            for (String replica : replicas) {
                var actual = AppRegistrationCheck.definition(group, () -> reference.get().snapshot(replica).workerId());
                var witnessed = WorkerEventDefinition.extension("app.registration.check", WorkerEventParameterResolvers.jsonMap(), input -> {
                    long start = System.nanoTime(); boolean failed = false;
                    try { return actual.handler().execute(input); }
                    catch (WorkerException error) { failed = error.errorCode() == WorkerErrorCode.EVENT_EXECUTION_FAILED; throw error; }
                    finally {
                        if (entered != null) {
                            entered.countDown();
                            try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Report gate was not released"); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        }
                        if (attempts.size() >= 500) throw new AssertionError("Bounded attempt witness overflow");
                        attempts.add(new Attempt(group, (String) input.get("number"), failed, System.nanoTime() - start));
                    }
                });
                builder.replica(replica, () -> Map.of("simulated", "true"), List.of(witnessed));
            }
            var manager = builder.build(); reference.set(manager); return manager;
        }
        List<String> readyFacts(JavaWorkerManager manager, String group) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            do {
                var ids = replicas.stream().map(replica -> manager.snapshot(replica).workerId())
                        .filter(Objects::nonNull).toList();
                if (ids.size() == replicas.size() && context.getBean(WorkerProperties.class).loadWorkerFacts(group, ids).values().stream()
                        .filter(Objects::nonNull).count() == replicas.size()) return ids;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Worker Facts publication was not established");
        }
        void assertAllocationProjection(String group, List<String> ids) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                var snapshot = observations.snapshot();
                var times = new LinkedHashMap<String, List<Long>>();
                snapshot.stream().filter(observation -> observation.workerGroupId().equals(group)
                        && observation.observationEventName().equals("worker.assigned")
                        && observation.messageEventName().equals("extension.worker.app.registration.check"))
                        .forEach(observation -> observation.workerIds().forEach(id -> times.computeIfAbsent(id, ignored -> new ArrayList<>())
                                .add(observation.observedAtMillis())));
                var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, ids);
                boolean matches = !times.isEmpty();
                for (var worker : times.entrySet()) {
                    long last = worker.getValue().stream().mapToLong(Long::longValue).max().orElseThrow();
                    long count = worker.getValue().stream().filter(time -> time / 60_000 == last / 60_000).count();
                    var values = facts.get(worker.getKey()).platformProperties();
                    matches &= values.get("lastAssignedAt") instanceof Number at && at.longValue() == last
                            && values.get("windowAssignmentCount") instanceof Number n && n.longValue() == count;
                }
                if (matches && observations.snapshot().equals(snapshot)) return;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Platform window did not match the bounded allocation source witness");
        }
        String create(String request, String app, int count) throws Exception {
            int offset = ++sequence * 1000;
            var numbers = IntStream.range(offset, offset + count).mapToObj(i -> "+8613800" + String.format("%06d", i)).toList();
            var body = Map.of("requestId", request, "checkId", app, "country", "CN");
            var response = http.send(request("/api/v1/app-checks/tasks", "proof", "admin")
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(response.body()).get("taskId");
            assertThat(post("/api/v1/app-checks/tasks/" + id + "/seeds:import", String.join("\n", numbers), "text/plain").statusCode()).isEqualTo(200);
            assertThat(approve(id, count).statusCode()).isEqualTo(200);
            return id;
        }
        HttpResponse<String> post(String path, String body, String contentType) throws Exception {
            return postAs(path, body, contentType, "proof", "admin");
        }
        HttpRequest.Builder request(String path, String user, String role) {
            return HttpRequest.newBuilder(base.resolve(path)).header("X-XA-Dev-User",
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8)))
                    .header("X-XA-Dev-Role", role);
        }
        HttpResponse<String> postAs(String path, String body, String contentType, String user, String role) throws Exception {
            return http.send(request(path, user, role).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> settled(String id, int total) throws Exception {
            return settled(id, total, 45);
        }
        Map<String, Object> settled(String id, int total, long seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            do {
                var detail = detail(id); var task = task(detail);
                if (Long.valueOf(total).equals(task.get("totalCount")) && Long.valueOf(0).equals(task.get("activeCount"))
                        && "terminal".equals(task.get("state"))
                        && (long) task.get("succeededCount") + (long) task.get("failedCount") == total
                        && !rows(detail).isEmpty()) return detail;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Task did not close its finite execution observations");
        }
        Map<String, Object> detail(String id) { return TaskObservations.detail(http, base, CALLER, "app-checks", id); }
        Map<String, Object> get(String path) throws Exception {
            var response = http.send(request(path, "proof", "admin").timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); return Jsons.parseObject(response.body());
        }
        HttpResponse<String> putConfiguration(Map<String, Object> input) throws Exception {
            return http.send(request("/api/v1/app-checks/configuration", "proof", "admin")
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(Jsons.toJson(input))).build(), HttpResponse.BodyHandlers.ofString());
        }
        void restart(String... overrides) {
            context.close();
            var next = new ArrayList<>(List.of(arguments)); next.addAll(List.of(overrides));
            arguments = next.toArray(String[]::new);
            context = application.run(arguments);
        }
        public void close() {
            context.close(); http.close();
            var client = RedisClient.create(redisUrl);
            try (var connection = client.connect()) {
                ScanCursor cursor = ScanCursor.INITIAL;
                do { var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                    if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
                } while (!cursor.isFinished());
            } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); }
        }
        static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    }
}
