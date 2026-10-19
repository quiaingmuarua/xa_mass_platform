package com.xa.mass.serverboot;

import com.xa.mass.server.assembly.pacer.WorkerObservationWitness;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import static org.assertj.core.api.Assertions.*;

/** Only Project membership changes between two real Server/Worker runs in the same scope. */
@Tag("scenario-composition")
class ProjectWorkerBindingsIntegrationTest {
    private static final String A = "project-window-a", B = "project-window-b";
    private static final String EVENT = "extension.worker.string.md5", OTHER = "extension.worker.string.sha1";
    private static final String POOL = "project-window", FUNCTION = "worker.project-window.available";
    private final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");

    @Test void addingOnlyAProjectMemberEnablesPoolQueryAndOneFeedback() throws Exception {
        String scope = "test_project_bindings_" + UUID.randomUUID().toString().replace("-", "");
        Path root = Files.createTempDirectory(Path.of("build"), "project-bindings-").toAbsolutePath();
        var before = configuration(List.of(A));
        var after = configuration(List.of(A, B));
        var withoutMembership = new LinkedHashMap<>(after);
        withoutMembership.put("xa.mass.project-assembly.projects", before.get("xa.mass.project-assembly.projects"));
        assertThat(withoutMembership).isEqualTo(before);
        Path first = root.resolve("before.yaml"), second = root.resolve("after.yaml");
        // JSON is a YAML subset; these are the actual external startup documents, not a parallel model.
        Files.writeString(first, Jsons.toJson(before)); Files.writeString(second, Jsons.toJson(after));
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("beforeSha256", hash(Files.readAllBytes(first)));
        evidence.put("afterSha256", hash(Files.readAllBytes(second)));
        evidence.put("onlyChangedPath", "xa.mass.project-assembly.projects[1].worker-group-ids");
        try {
            run(first, root, scope, A, false);
            run(second, root, scope, B, true);
            evidence.put("passed", true);
            evidence.put("groups", List.of(A, B));
            evidence.put("rawItemsPerTask", 3);
            evidence.put("selectedFeedbackItems", 2);
            Files.writeString(root.resolve("evidence.json"), Jsons.toJson(evidence));
        } finally { cleanup(scope); }
    }

    private static Map<String,Object> configuration(List<String> members) {
        var binding = Map.of("pools", Map.of(POOL, Map.of()), "feedbacks", List.of("digest"));
        return Map.of(
                "xa.mass.project-assembly.projects", List.of(
                    Map.of("project-id", "default", "worker-group-ids", List.of()),
                    Map.of("project-id", "validation", "worker-group-ids", members),
                    Map.of("project-id", "overlap", "worker-group-ids", List.of(A))),
                "xa.mass.worker-assembly.group-config-json", Jsons.toJson(Map.of(
                    A, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER)),
                    B, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER)))),
                "xa.mass.worker-assembly.fixed-window", Map.of(
                    "pools", Map.of(POOL, Map.of("function-name", FUNCTION,
                        "timestamp-property", "projectObservedAt", "count-property", "projectObservedCount",
                        "window-millis", 60000, "max-count", 10)),
                    "feedbacks", Map.of("digest", Map.of("pool-name", POOL, "message-events", List.of(EVENT))),
                    "projects", Map.of("validation", binding, "overlap", binding)));
    }

    @SuppressWarnings("unchecked")
    private void run(Path configuration, Path root, String scope, String group, boolean expanded) throws Exception {
        int serverPort = port(), adapterPort = port();
        URI base = URI.create("http://127.0.0.1:" + serverPort);
        var application = new SpringApplication(ServerBootConfiguration.class);
        application.setRegisterShutdownHook(false);
        var observations = new WorkerObservationWitness();
        application.addInitializers(context -> context.getBeanFactory().addBeanPostProcessor(observations));
        var args = List.of("--spring.profiles.active=consumer", "--spring.config.additional-location=" + configuration.toUri(),
                "--server.port=" + serverPort, "--logging.level.root=ERROR", "--xa.mass.redis.url=" + redis,
                "--xa.mass.redis.scope=" + scope, "--xa.mass.users.seed=classpath:users/proof-directory.json",
                "--xa.mass.task-retention.enabled=false",
                "--xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port=" + adapterPort,
                "--xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri=ws://127.0.0.1:" + adapterPort + "/api/v1/worker-delivery/websocket");
        try (var context = application.run(args.toArray(String[]::new)); var http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(com.xa.mass.scenario.appchecks.AppCheckTaskService.class)).isEmpty();
            var b = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/" + B + "/bindings", null);
            assertThat(b.get("workerGroup")).isNotNull();
            assertThat((List<?>) b.get("fixedWindowPools")).hasSize(expanded ? 1 : 0);
            assertThat((List<?>) b.get("feedbacks")).hasSize(expanded ? 1 : 0);
            if (!expanded) {
                var rejected = send(http, base, "POST", "/api/v1/tasks", create(B));
                assertThat(rejected.statusCode()).isEqualTo(400);
            }
            var selected = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/" + group + "/bindings", null);
            assertThat((List<Map<String,Object>>) selected.get("fixedWindowPools")).singleElement().satisfies(row ->
                    assertThat(row).containsEntry("poolName", POOL).containsEntry("functionName", FUNCTION));
            assertThat((List<?>) selected.get("feedbacks")).hasSize(1);
            Path hostConfig = root.resolve(group + "-host.json");
            Files.writeString(hostConfig, Jsons.toJson(Map.of("runtimeApiBaseUrl", base.toString(), "controlPort", 0,
                    "sandboxRoot", root.resolve("data/scenario-workers").toString(), "workerGroups", Map.of(group, Map.of(
                        "count", 1, "events", List.of(EVENT, OTHER), "propertiesTemplate", Map.of("fixture", "project-binding"),
                        "newEnvironment", false)))));
            var hostFailure = new AtomicReference<Throwable>();
            var host = new Thread(() -> {
                try { com.xa.mass.workersimulator.WorkerSimulatorMain.main(new String[]{"--config", hostConfig.toString()}); }
                catch (Throwable failure) { hostFailure.set(failure); }
            }, "project-binding-proof-host");
            host.start();
            try {
                String task = (String) call(http, base, "POST", "/api/v1/tasks", create(group)).get("taskId");
                var items = List.of(item("one", EVENT, "a"), item("two", EVENT, "abc"), item("other", OTHER, "abc"));
                call(http, base, "POST", "/api/v1/tasks/" + task + "/items", items);
                call(http, base, "POST", "/api/v1/tasks/" + task + "/approve", Map.of("expectedItemCount", 3));
                await(() -> {
                    if (hostFailure.get() != null) throw new AssertionError("Host failed", hostFailure.get());
                    return "terminal".equals(call(http, base, "GET", "/api/v1/projects/default/tasks/" + task, null).get("scoreBand"));
                });
                var ids = List.of("one", "two", "other");
                await(() -> call(http, base, "POST", "/api/v1/tasks/" + task + "/results:load", ids).size() == 3);
                var results = call(http, base, "POST", "/api/v1/tasks/" + task + "/results:load", ids);
                var states = call(http, base, "POST", "/api/v1/tasks/" + task + "/items:states", ids);
                var expected = Map.of("one", "0cc175b9c0f1b6a831c399e269772661", "two", "900150983cd24fb0d6963f7d28e17f72",
                        "other", "a9993e364706816aba3e25717850c26c9cd0d89d");
                for (String id : ids) {
                    assertThat((Map<String,Object>) states.get(id)).containsEntry("band", "terminal").containsEntry("outcomeName", "succeeded");
                    var result = (Map<String,Object>) results.get(id);
                    assertThat(result.get("status")).isEqualTo("succeeded");
                    var content = Jsons.parseObject((String) result.get("opaqueResultPayload"));
                    assertThat(content.get(id.equals("other") ? "sha1" : "md5")).isEqualTo(expected.get(id));
                }
                var raw = call(http, base, "GET", "/api/v1/tasks/" + task + "/results:preview", null);
                assertThat((List<Map<String,Object>>) raw.get("results")).hasSize(3).allSatisfy(row -> {
                    var input = (Map<String,Object>) row.get("item");
                    assertThat((Map<String,Object>) input.get("workerSelector"))
                            .containsEntry("executorName", FUNCTION).containsEntry("input", Map.of());
                });
                await(() -> {
                    var selectedObservations = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(group)
                            && row.messageEventName().equals(EVENT)).toList();
                    if (selectedObservations.stream().mapToInt(row -> row.workerIds().size()).sum() < 2) return false;
                    String worker = selectedObservations.getFirst().workerIds().getFirst();
                    assertThat(selectedObservations.stream().flatMap(row -> row.workerIds().stream()).distinct().toList()).containsExactly(worker);
                    long last = selectedObservations.stream().mapToLong(row -> row.observedAtMillis()).max().orElseThrow();
                    long count = selectedObservations.stream().filter(row -> row.observedAtMillis() / 60000 == last / 60000)
                            .mapToLong(row -> row.workerIds().size()).sum();
                    var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, List.of(worker)).get(worker);
                    return facts != null && Objects.equals(facts.platformProperties().get("projectObservedAt"), last)
                            && Objects.equals(facts.platformProperties().get("projectObservedCount"), count);
                });
                var exported = send(http, base, "POST", "/api/v1/tasks/" + task + "/results:export", null);
                assertThat(exported.statusCode()).isEqualTo(200);
                assertThat(exported.body().lines().count()).isEqualTo(3);
                assertThat(hostFailure.get()).isNull();
            } finally { host.interrupt(); host.join(10000); assertThat(host.isAlive()).isFalse(); }
        }
    }

    private static Map<String,Object> create(String group) {
        return Map.of("requestId", "project-binding-" + group, "projectId", "default", "workerGroupId", group,
                "refill", List.of(Map.of("poolName", POOL, "target", Map.of(), "count", 100)));
    }
    private static Map<String,Object> item(String id, String event, String value) {
        return Map.of("messageId", id, "eventCode", event, "payload", Map.of("value", value),
                "workerSelector", Map.of("executorName", FUNCTION, "input", Map.of()));
    }
    private static Map<String,Object> call(HttpClient http, URI base, String method, String path, Object body) throws Exception {
        var result = send(http, base, method, path, body);
        assertThat(result.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        return Jsons.parseObject(result.body());
    }
    private static HttpResponse<String> send(HttpClient http, URI base, String method, String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("X-XA-Dev-User", "cHJvb2Y").header("X-XA-Dev-Role", "admin").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static void await(Check condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do { if (condition.test()) return; Thread.sleep(50); } while (System.nanoTime() < end);
        throw new AssertionError("Project binding proof did not converge");
    }
    private interface Check { boolean test() throws Exception; }
    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private void cleanup(String scope) {
        var client = RedisClient.create(redis);
        try (var connection = client.connect()) {
            ScanCursor cursor = ScanCursor.INITIAL;
            do { var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(100));
                if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
            } while (!cursor.isFinished());
        } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); }
    }
}
