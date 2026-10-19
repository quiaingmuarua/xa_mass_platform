package com.xa.mass.serverboot;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.WorkerProperties;
import io.lettuce.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Same production classes across two starts; only deployment-owned files change. */
@Tag("scenario-composition")
class BatchChecksExternalDeploymentIntegrationTest {
    private static final String A = "deployment-alpha", B = "deployment-beta";
    private static final String EVENT = "extension.worker.android.string.digest";
    private static final String POOL = "deployment-window", QUERY = "worker.deployment.available";
    private static final Map<String,String> GOLDENS = Map.of("a", "0cc175b9c0f1b6a831c399e269772661",
            "abc", "900150983cd24fb0d6963f7d28e17f72", "中文", "a7bac2239fcdcb3a067903d8077c4a07");
    @TempDir Path root;
    private final String scope = "test_external_checks_" + UUID.randomUUID().toString().replace("-", "");
    private final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
    private final HttpClient http = HttpClient.newHttpClient();
    private URI base;

    @Test @Timeout(120) @SuppressWarnings("unchecked")
    void externalFilesAddASecondGroupWithoutChangingArtifactsOrExistingTaskSnapshots() throws Exception {
        Files.writeString(root.resolve("seed.json"), Jsons.toJson(Map.of("common", Map.of("typeDefaults", Map.of()),
                "checks", Map.of("first-check", check(A)))));
        writeHost(List.of(A));
        String first;
        Map<String,Object> original;
        try (var server = start(); var worker = worker(A)) {
            assertThat(server.getBean(ProjectDirectory.class).require("app-checks").workerGroupIds()).containsExactly(A);
            assertThat(send("GET", "/api/v1/messages/catalog", null, "application/json").statusCode()).isEqualTo(404);
            assertThat(send("GET", "/api/v1/sms/catalog", null, "application/json").statusCode()).isEqualTo(404);
            worker.start();
            call("POST", "/api/v1/users/1000/credits/adjustments", Map.of("requestId", UUID.randomUUID().toString(), "delta", 20, "reason", "External file proof"));
            first = execute(server, worker, A, "first-check", "first-request");
            original = call("GET", "/api/v1/projects/app-checks/tasks/" + first, null);
            var saved = (Map<String,Object>) call("GET", "/api/v1/app-checks/configuration", null).get("saved");
            var document = (Map<String,Object>) saved.get("configuration");
            ((Map<String,Object>) document.get("checks")).put("second-check", check(B));
            assertThat(call("PUT", "/api/v1/app-checks/configuration", Map.of(
                    "expectedFingerprint", saved.get("fingerprint"), "configuration", document))).containsEntry("restartRequired", true);
            assertThat((List<?>) call("GET", "/api/v1/app-checks/catalog", null).get("checks")).hasSize(1);
            assertThat(server.getBean(ProjectDirectory.class).require("app-checks").workerGroupIds()).containsExactly(A);
        }
        writeHost(List.of(A, B));
        Files.writeString(root.resolve("seed.json"), "deliberately invalid: an existing saved document must win");
        try (var server = start(); var worker = worker(B)) {
            assertThat(server.getBean(ProjectDirectory.class).require("app-checks").workerGroupIds()).containsExactly(A, B);
            assertThat((List<?>) call("GET", "/api/v1/app-checks/catalog", null).get("checks")).hasSize(2);
            assertThat(call("POST", "/api/v1/app-checks/tasks", Map.of("requestId", "first-request", "checkId", "first-check")))
                    .containsEntry("taskId", first);
            assertThat(call("GET", "/api/v1/projects/app-checks/tasks/" + first, null).get("task")).isEqualTo(original.get("task"));
            assertThat((List<?>) call("GET", "/api/v1/app-checks/tasks/" + first + "/results", null).get("results")).hasSize(3);
            worker.start();
            execute(server, worker, B, "second-check", "second-request");
            assertThat(call("GET", "/api/v1/users/1000/credits", null)).containsEntry("balance", 14L);
        }
    }

    private Map<String,Object> check(String group) {
        return Map.of("label", "External digest " + group, "checkType", "string-digest-md5-prefix-v1", "workerGroupId", group,
                "unitPrice", 1, "parameters", Map.of(), "execution", Map.of("eventCode", EVENT, "seedsPerItem", 1,
                "refill", List.of(Map.of("poolName", POOL, "target", Map.of(), "count", 100)),
                "workerSelector", Map.of("executorName", QUERY, "input", Map.of())));
    }

    private void writeHost(List<String> groups) throws Exception {
        var definitions = new LinkedHashMap<String,Object>();
        var bindings = new LinkedHashMap<String,Object>();
        for (String group : groups) {
            definitions.put(group, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT)));
            bindings.put(group, Map.of("pools", Map.of(POOL, Map.of())));
        }
        Files.writeString(root.resolve("Group 注册.json"), Jsons.toJson(definitions));
        int api, adapter;
        try (var apiSocket = new ServerSocket(0); var adapterSocket = new ServerSocket(0)) {
            api = apiSocket.getLocalPort(); adapter = adapterSocket.getLocalPort();
        }
        var host = new LinkedHashMap<String,Object>();
        host.put("server.port", api); host.put("logging.level.root", "ERROR");
        host.put("xa.mass.redis.url", redis); host.put("xa.mass.redis.scope", scope);
        host.put("xa.mass.task-retention.enabled", false); host.put("xa.mass.kernel-pacer.enabled", true);
        host.put("xa.mass.scenarios.app-checks.enabled", true);
        host.put("xa.mass.scenarios.app-checks.configuration.path", root.resolve("data/configuration.json").toString());
        host.put("xa.mass.scenarios.app-checks.configuration.seed", root.resolve("seed.json").toUri().toString());
        host.put("xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port", adapter);
        host.put("xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri", "ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket");
        host.put("xa.mass.worker-assembly.fixed-window", Map.of("groups", bindings, "pools", Map.of(POOL,
                Map.of("function-name", QUERY, "timestamp-property", "deploymentAt", "count-property", "deploymentCount",
                        "window-millis", 60000, "max-count", 10, "feedback", Map.of("message-events", List.of(EVENT))))));
        // JSON is a YAML subset and loads through Spring's actual external configuration path.
        Files.writeString(root.resolve("Runtime 配置.yaml"), Jsons.toJson(host));
    }

    private ConfigurableApplicationContext start() throws Exception {
        var app = new SpringApplication(ServerBootConfiguration.class);
        app.setRegisterShutdownHook(false);
        var context = app.run("--spring.profiles.active=consumer", "--spring.config.additional-location=" + root.resolve("Runtime 配置.yaml").toUri(),
                "--xa.mass.worker-assembly.group-config-json=" + Files.readString(root.resolve("Group 注册.json")));
        base = URI.create("http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port"));
        return context;
    }

    private JavaWorkerManager worker(String group) {
        return JavaWorkerManager.builder(base, group, WorkerTransportType.WEBSOCKET).replica("external", () -> Map.of("proof", "external-config"),
                List.of(WorkerEventDefinition.extension("android.string.digest", WorkerEventParameterResolvers.jsonMap(), input -> {
                    try { return Jsons.toJson(Map.of("algorithm", "MD5", "input", input.get("value"), "digest",
                            HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(((String) input.get("value")).getBytes(StandardCharsets.UTF_8))))); }
                    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                }))).build();
    }

    @SuppressWarnings("unchecked")
    private String execute(ConfigurableApplicationContext context, JavaWorkerManager worker, String group, String check, String requestId) throws Exception {
        String id = (String) call("POST", "/api/v1/app-checks/tasks", Map.of("requestId", requestId, "checkId", check)).get("taskId");
        var imported = send("POST", "/api/v1/app-checks/tasks/" + id + "/seeds:import", "a\nabc\n中文", "text/plain; charset=utf-8");
        assertThat(imported.statusCode()).isEqualTo(200);
        assertThat(Jsons.parseObject(imported.body())).containsEntry("itemCount", 3L).containsEntry("addedCount", 3L);
        assertThat(call("GET", "/api/v1/app-checks/tasks/" + id + "/credits", null)).containsEntry("amount", 3L);
        var approval = Map.of("expectedItemCount", 3, "credits", Map.of("requestId", UUID.randomUUID().toString(), "expectedSeedCount", 3, "expectedAmount", 3));
        call("POST", "/api/v1/tasks/" + id + "/approve", approval);
        call("POST", "/api/v1/tasks/" + id + "/approve", approval);
        await(() -> "terminal".equals(call("GET", "/api/v1/projects/app-checks/tasks/" + id, null).get("scoreBand")));
        var raw = (List<Map<String,Object>>) call("GET", "/api/v1/tasks/" + id + "/results:preview", null).get("results");
        assertThat(raw).hasSize(3);
        var ids = raw.stream().map(row -> (String) row.get("messageId")).toList();
        var results = call("POST", "/api/v1/tasks/" + id + "/results:load", ids);
        var states = call("POST", "/api/v1/tasks/" + id + "/items:states", ids);
        var observed = new HashSet<String>();
        for (var row : raw) {
            var item = (Map<String,Object>) row.get("item");
            assertThat(item.get("eventCode")).isEqualTo(EVENT);
            assertThat(item.get("workerSelector")).isEqualTo(Map.of("executorName", QUERY, "input", Map.of()));
            var result = (Map<String,Object>) results.get(row.get("messageId"));
            assertThat(result).containsEntry("status", "succeeded");
            var content = Jsons.parseObject((String) result.get("opaqueResultPayload"));
            String seed = (String) content.get("input");
            assertThat(GOLDENS).containsKey(seed);
            assertThat(content).containsEntry("algorithm", "MD5").containsEntry("digest", GOLDENS.get(seed));
            assertThat(item.get("payload")).isEqualTo(Map.of("algorithm", "MD5", "value", seed));
            observed.add(seed);
            assertThat((Map<String,Object>) states.get(row.get("messageId"))).containsEntry("band", "terminal").containsEntry("outcomeName", "succeeded");
        }
        assertThat(observed).containsExactlyInAnyOrderElementsOf(GOLDENS.keySet());
        var rows = (List<Map<String,Object>>) call("GET", "/api/v1/app-checks/tasks/" + id + "/results", null).get("results");
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            String seed = (String) row.get("seed");
            assertThat(row).doesNotContainKey("contentError").containsEntry("matched", GOLDENS.get(seed).startsWith("0"));
            assertThat((Map<String,Object>) row.get("details")).containsEntry("md5", GOLDENS.get(seed));
        });
        var csv = send("POST", "/api/v1/app-checks/tasks/" + id + "/results:export?filter=all", null, "application/json");
        assertThat(csv.statusCode()).isEqualTo(200); assertThat(csv.body().lines().count()).isEqualTo(4);
        assertThat(csv.headers().firstValue("X-Export-Count")).contains("3");
        for (String digest : GOLDENS.values()) assertThat(csv.body()).contains(digest);
        await(() -> {
            String workerId = worker.snapshot("external").workerId();
            if (workerId == null) return false;
            var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, List.of(workerId)).get(workerId);
            return facts != null && facts.platformProperties().get("deploymentCount") instanceof Number count && count.longValue() > 0
                    && facts.platformProperties().get("deploymentAt") instanceof Number at && at.longValue() > 0;
        });
        return id;
    }

    private Map<String,Object> call(String method, String path, Object body) throws Exception {
        var response = send(method, path, body == null ? null : Jsons.toJson(body), "application/json");
        assertThat(response.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        return Jsons.parseObject(response.body());
    }
    private HttpResponse<String> send(String method, String path, String body, String contentType) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("X-XA-Dev-User", "MTAwMA").header("X-XA-Dev-Role", "admin").header("Content-Type", contentType)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void await(Check condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do { if (condition.test()) return; Thread.sleep(50); } while (System.nanoTime() < end);
        throw new AssertionError("External deployment did not converge");
    }
    private interface Check { boolean test() throws Exception; }
    @AfterEach void cleanup() {
        var client = RedisClient.create(redis);
        try (var connection = client.connect()) {
            ScanCursor cursor = ScanCursor.INITIAL;
            do { var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(100));
                if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
            } while (!cursor.isFinished());
        } finally { client.shutdown(); }
    }
}
