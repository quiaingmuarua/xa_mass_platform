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

/** Project admission and Group resource activation change independently across real restarts. */
@Tag("scenario-composition")
class GroupWindowBindingsIntegrationTest {
    private static final String A = "project-window-a", B = "group-window-b", C = "group-window-c";
    private static final String EVENT = "extension.worker.string.md5", OTHER = "extension.worker.string.sha1";
    private static final String POOL = "project-window", FUNCTION = "worker.project-window.available";
    private final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");

    @Test void projectMembershipDoesNotEnableResourcesAndOneGroupReferenceEnablesTheCompleteWindow() throws Exception {
        String scope = "test_project_bindings_" + UUID.randomUUID().toString().replace("-", "");
        Path root = Files.createTempDirectory(Path.of("build"), "project-bindings-").toAbsolutePath();
        var before = configuration(List.of(A), false);
        var after = configuration(List.of(A, B), false);
        var withoutMembership = new LinkedHashMap<>(after);
        withoutMembership.put("xa.mass.project-assembly.projects", before.get("xa.mass.project-assembly.projects"));
        assertThat(withoutMembership).isEqualTo(before);
        var enabled = configuration(List.of(A, B), true);
        var sameProject = new LinkedHashMap<>(enabled);
        sameProject.put("xa.mass.worker-assembly.fixed-window", after.get("xa.mass.worker-assembly.fixed-window"));
        assertThat(sameProject).isEqualTo(after);
        Path first = root.resolve("before.yaml"), second = root.resolve("project-only.yaml"), third = root.resolve("group-binding.yaml");
        // JSON is a YAML subset; these are the actual external startup documents, not a parallel model.
        Files.writeString(first, Jsons.toJson(before)); Files.writeString(second, Jsons.toJson(after)); Files.writeString(third, Jsons.toJson(enabled));
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("beforeSha256", hash(Files.readAllBytes(first)));
        evidence.put("afterSha256", hash(Files.readAllBytes(second)));
        evidence.put("groupBindingSha256", hash(Files.readAllBytes(third)));
        evidence.put("projectOnlyChange", "xa.mass.project-assembly.projects[1].worker-group-ids");
        evidence.put("resourceOnlyChange", "xa.mass.worker-assembly.fixed-window.groups");
        try {
            run(first, root, scope, A, "validation", 0);
            run(first, root, scope, C, "default", 0);
            run(second, root, scope, B, "validation", 1);
            run(third, root, scope, B, "validation", 2);
            evidence.put("passed", true);
            evidence.put("groups", List.of(A, B, C));
            evidence.put("rawItemsPerTask", 3);
            evidence.put("selectedFeedbackItems", 2);
            Files.writeString(root.resolve("evidence.json"), Jsons.toJson(evidence));
        } finally { cleanup(scope); }
    }

    private static Map<String,Object> configuration(List<String> members, boolean enableB) {
        var groups = new LinkedHashMap<String,Object>();
        for (String group : enableB ? List.of(A, B, C) : List.of(A, C)) groups.put(group, Map.of("pools", Map.of(POOL, Map.of())));
        return Map.of(
                "xa.mass.project-assembly.projects", List.of(
                    Map.of("project-id", "default", "worker-group-ids", List.of()),
                    Map.of("project-id", "validation", "worker-group-ids", members)),
                "xa.mass.worker-assembly.group-config-json", Jsons.toJson(Map.of(
                    A, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER)),
                    B, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER)),
                    C, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER)))),
                "xa.mass.worker-matching.groups." + B, Map.of("pools", List.of("any"), "functions", List.of("worker.any")),
                "xa.mass.worker-assembly.fixed-window", Map.of(
                    "pools", Map.of(POOL, Map.of("function-name", FUNCTION,
                        "timestamp-property", "projectObservedAt", "count-property", "projectObservedCount",
                        "window-millis", 60000, "max-count", 10, "feedback", Map.of("message-events", List.of(EVENT)))),
                    "groups", groups));
    }

    @SuppressWarnings("unchecked")
    private void run(Path configuration, Path root, String scope, String group, String project, int phase) throws Exception {
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
            var directory = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/bindings", null);
            assertThat((List<String>) directory.get("workerGroupIds")).containsExactlyElementsOf(new TreeSet<>(List.of(A, B, C)));
            var b = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/" + B + "/bindings", null);
            assertThat(b.get("workerGroup")).isNotNull();
            assertThat(((List<Map<String,Object>>) b.get("projects")).stream().map(row -> row.get("projectId")))
                    .containsExactlyElementsOf(phase == 0 ? List.of("default") : List.of("default", "validation"));
            assertThat((List<?>) b.get("fixedWindowPools")).hasSize(phase == 2 ? 1 : 0);
            if (phase < 2) {
                var rejected = send(http, base, "POST", "/api/v1/tasks", create(B, "default", phase, true));
                assertThat(rejected.statusCode()).isEqualTo(400);
            }
            assertThat(send(http, base, "POST", "/api/v1/tasks", create(C, "validation", phase, true)).statusCode()).isEqualTo(400);
            if (phase == 0) assertThat(send(http, base, "POST", "/api/v1/tasks", create(B, "validation", phase, false)).statusCode()).isEqualTo(400);
            boolean window = !group.equals(B) || phase == 2;
            String query = window ? FUNCTION : "worker.any";
            var selected = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/" + group + "/bindings", null);
            assertThat((List<?>) selected.get("fixedWindowPools")).hasSize(window ? 1 : 0);
            if (window) assertThat((List<Map<String,Object>>) selected.get("fixedWindowPools")).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("poolName", POOL).containsEntry("functionName", FUNCTION);
                assertThat((Map<String,Object>) row.get("feedback")).containsEntry("messageEvents", List.of(EVENT));
            });
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
                String task = (String) call(http, base, "POST", "/api/v1/tasks", create(group, project, phase, window)).get("taskId");
                var items = List.of(item("one", EVENT, "a", query), item("two", EVENT, "abc", query), item("other", OTHER, "abc", query));
                call(http, base, "POST", "/api/v1/tasks/" + task + "/items", items);
                call(http, base, "POST", "/api/v1/tasks/" + task + "/approve", Map.of("expectedItemCount", 3));
                await(() -> {
                    if (hostFailure.get() != null) throw new AssertionError("Host failed", hostFailure.get());
                    return "terminal".equals(call(http, base, "GET", "/api/v1/projects/" + project + "/tasks/" + task, null).get("scoreBand"));
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
                            .containsEntry("executorName", query).containsEntry("input", Map.of());
                });
                if (window) await(() -> {
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
                if (!window) {
                    String worker = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(group)).findFirst().orElseThrow().workerIds().getFirst();
                    var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, List.of(worker)).get(worker);
                    assertThat(facts.platformProperties()).doesNotContainKeys("projectObservedAt", "projectObservedCount");
                }
                if (phase == 2) {
                    String worker = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(group)).findFirst().orElseThrow().workerIds().getFirst();
                    int observedBefore = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(group) && row.messageEventName().equals(EVENT))
                            .mapToInt(row -> row.workerIds().size()).sum();
                    String direct = (String) call(http, base, "POST", "/api/v1/tasks", Map.of("requestId", "direct-feedback", "projectId", project,
                            "workerGroupId", group, "refill", List.of())).get("taskId");
                    call(http, base, "POST", "/api/v1/tasks/" + direct + "/items", List.of(Map.of("messageId", "direct", "eventCode", EVENT,
                            "payload", Map.of("value", "a"), "workerSelector", Map.of("executorName", "workerId", "input", worker))));
                    call(http, base, "POST", "/api/v1/tasks/" + direct + "/approve", Map.of("expectedItemCount", 1));
                    await(() -> "succeeded".equals(((Map<?,?>) call(http, base, "POST", "/api/v1/tasks/" + direct + "/results:load", List.of("direct")).get("direct")).get("status")));
                    var directResult = (Map<String,Object>) call(http, base, "POST", "/api/v1/tasks/" + direct + "/results:load", List.of("direct")).get("direct");
                    assertThat(directResult).containsEntry("status", "succeeded");
                    assertThat(Jsons.parseObject((String) directResult.get("opaqueResultPayload"))).containsEntry("md5", "0cc175b9c0f1b6a831c399e269772661");
                    await(() -> {
                        var selectedObservations = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(group) && row.messageEventName().equals(EVENT)).toList();
                        if (selectedObservations.stream().mapToInt(row -> row.workerIds().size()).sum() <= observedBefore) return false;
                        long last = selectedObservations.stream().mapToLong(row -> row.observedAtMillis()).max().orElseThrow();
                        long count = selectedObservations.stream().filter(row -> row.observedAtMillis() / 60000 == last / 60000).mapToLong(row -> row.workerIds().size()).sum();
                        var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, List.of(worker)).get(worker);
                        return Objects.equals(facts.platformProperties().get("projectObservedAt"), last) && Objects.equals(facts.platformProperties().get("projectObservedCount"), count);
                    });
                }
                var exported = send(http, base, "POST", "/api/v1/tasks/" + task + "/results:export", null);
                assertThat(exported.statusCode()).isEqualTo(200);
                assertThat(exported.body().lines().count()).isEqualTo(3);
                assertThat(hostFailure.get()).isNull();
            } finally { host.interrupt(); host.join(10000); assertThat(host.isAlive()).isFalse(); }
        }
    }

    private static Map<String,Object> create(String group, String project, int phase, boolean window) {
        return Map.of("requestId", "group-binding-" + group + "-" + phase, "projectId", project, "workerGroupId", group,
                "refill", List.of(Map.of("poolName", window ? POOL : "any", "target", Map.of(), "count", 100)));
    }
    private static Map<String,Object> item(String id, String event, String value, String query) {
        return Map.of("messageId", id, "eventCode", event, "payload", Map.of("value", value),
                "workerSelector", Map.of("executorName", query, "input", Map.of()));
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
