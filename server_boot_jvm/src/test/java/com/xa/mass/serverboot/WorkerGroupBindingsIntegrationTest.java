package com.xa.mass.serverboot;

import com.xa.mass.server.assembly.pacer.WorkerObservationWitness;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.WorkerProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

/** Config-only Group C, existing Simulator MD5 handlers, raw Runtime evidence and real business CSV. */
@Tag("scenario-composition")
class WorkerGroupBindingsIntegrationTest {
    private static final String GROUP = "checks-proof-c", POOL = "proof-window-c", FUNCTION = "proof.window.take";
    private static final String EVENT = "extension.worker.string.md5.batch", OTHER_EVENT = "extension.worker.string.md5";

    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(150)
    @SuppressWarnings("unchecked")
    void hostBindingWorksWithoutScenarioAndNewCheckOnlyAddsConfiguration(boolean scenario) throws Exception {
        String scope = "test_group_bindings_" + UUID.randomUUID().toString().replace("-", "");
        String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        Path root = Files.createTempDirectory(Path.of("build"), "group-bindings-").toAbsolutePath();
        int serverPort = port(), adapterPort = port();
        URI base = URI.create("http://127.0.0.1:" + serverPort);
        var groups = new LinkedHashMap<String,Object>();
        var preview = new YamlPropertySourceLoader().load("preview", new ClassPathResource("application-preview.yaml")).getFirst();
        if (scenario) {
            groups.putAll(Jsons.parseObject((String) preview.getProperty("xa.mass.worker-assembly.group-config-json")));
        }
        groups.put(GROUP, Map.of("attributes", Map.of(), "eventCodes", List.of(EVENT, OTHER_EVENT)));
        var args = new ArrayList<>(List.of("--spring.profiles.active=" + (scenario ? "preview" : "default"),
                "--server.port=" + serverPort, "--logging.level.root=ERROR", "--xa.mass.redis.url=" + redis,
                "--xa.mass.redis.scope=" + scope, "--xa.mass.users.seed=classpath:users/proof-directory.json",
                "--xa.mass.task-retention.enabled=false",
                "--xa.mass.worker-assembly.group-config-json=" + Jsons.toJson(groups),
                "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.type=WEBSOCKET",
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-host=127.0.0.1",
                "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapterPort,
                "--xa.mass.worker-endpoints.defaults.WEBSOCKET=products-websocket",
                "--xa.mass.worker-endpoints.endpoints.products-websocket.transport-type=WEBSOCKET",
                "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapterPort + "/api/v1/worker-delivery/websocket"));
        if (!scenario) {
            args.add("--xa.mass.project-assembly.projects[0].project-id=default");
            // Reuse Preview's transport parameters without importing its scenarios or resource bindings.
            ((Map<?, ?>) preview.getSource()).forEach((key, value) -> {
                String property = key.toString();
                if (property.startsWith("xa.mass.worker-delivery.adapter.instances.products-websocket.")
                        && !property.endsWith(".listen-port") && !property.endsWith(".type") && !property.endsWith(".listen-host"))
                    args.add("--" + property + "=" + value);
            });
        }
        String prefix = "--xa.mass.worker-assembly.fixed-window.";
        args.addAll(List.of(prefix + "pools." + POOL + ".function-name=" + FUNCTION,
                prefix + "pools." + POOL + ".timestamp-property=proofObservedAt",
                prefix + "pools." + POOL + ".count-property=proofObservedCount",
                prefix + "pools." + POOL + ".window-millis=60000", prefix + "pools." + POOL + ".max-count=1000",
                prefix + "pools." + POOL + ".feedback.message-events[0]=" + EVENT,
                prefix + "groups." + GROUP + ".pools." + POOL + ".window-millis=120000"));
        Path configurationFile = root.resolve("configuration.json");
        args.add("--xa.mass.scenarios.app-checks.configuration.path=" + configurationFile);
        args.add("--xa.mass.scenarios.app-checks.configuration.seed=classpath:app-checks/preview-configuration.json");
        if (scenario) {
            Map<String,Object> document;
            try (var input = getClass().getResourceAsStream("/app-checks/preview-configuration.json")) {
                document = Jsons.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            var checks = (Map<String,Object>) document.get("checks");
            checks.put("group-c-md5", Map.of("unitPrice", 1, "label", "Group C MD5", "checkType", "md5-prefix-demo-batch-v1", "workerGroupId", GROUP,
                    "execution", Map.of("eventCode", EVENT, "refill", supply(), "seedsPerItem", 10,
                            "workerSelector", Map.of("executorName", FUNCTION, "input", Map.of()))));
            Files.writeString(configurationFile, Jsons.toJson(document));
        }
        var observations = new WorkerObservationWitness();
        var application = new SpringApplication(ServerBootConfiguration.class);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> context.getBeanFactory().addBeanPostProcessor(observations));
        try (var context = application.run(args.toArray(String[]::new)); var http = HttpClient.newHttpClient()) {
            assertThat(context.getBeansOfType(com.xa.mass.scenario.appchecks.AppCheckTaskService.class)).hasSize(scenario ? 1 : 0);
            assertThat(Files.exists(configurationFile)).isEqualTo(scenario);
            assertThat(Files.exists(root.resolve("configuration.json.lock"))).isEqualTo(scenario);
            var binding = call(http, base, "GET", "/api/v1/runtime-view/worker-groups/" + GROUP + "/bindings", null);
            assertThat(binding.get("workerGroup")).isNotNull();
            assertThat((List<Map<String,Object>>) binding.get("fixedWindowPools")).singleElement().satisfies(row ->
                    assertThat(row).containsEntry("poolName", POOL).containsEntry("windowMillis", 120000L).containsEntry("maxCount", 1000L));
            assertThat((Map<String,Object>) ((List<Map<String,Object>>) binding.get("fixedWindowPools")).getFirst().get("feedback"))
                    .containsEntry("messageEvents", List.of(EVENT)).containsEntry("observationEventName", "worker.assigned");
            var hostConfig = root.resolve("host.json");
            Files.writeString(hostConfig, Jsons.toJson(Map.of("runtimeApiBaseUrl", base.toString(), "controlPort", 0,
                    "sandboxRoot", root.resolve("data/scenario-workers").toString(), "workerGroups", Map.of(GROUP, Map.of(
                    "count", 1, "events", List.of(EVENT, OTHER_EVENT), "propertiesTemplate", Map.of("fixture", "group-c"), "newEnvironment", false)))));
            var hostFailure = new AtomicReference<Throwable>();
            var host = new Thread(() -> {
                try { com.xa.mass.workersimulator.WorkerSimulatorMain.main(new String[]{"--config", hostConfig.toString()}); }
                catch (Throwable failure) { hostFailure.set(failure); }
            }, "group-binding-proof-host");
            host.start();
            try {
                var seeds = IntStream.range(0, 23).mapToObj(i -> "group-c-seed-" + i).toList();
                String task = (String) call(http, base, "POST", "/api/v1/tasks", Map.of("requestId", "raw-group-c",
                        "projectId", "default", "workerGroupId", GROUP, "refill", supply())).get("taskId");
                var items = new ArrayList<Map<String,Object>>();
                var expected = new LinkedHashMap<String,List<String>>();
                for (int start = 0; start < seeds.size(); start += 10) {
                    String id = "batch-" + start; var values = seeds.subList(start, Math.min(start + 10, seeds.size())); expected.put(id, values);
                    items.add(Map.of("messageId", id, "eventCode", EVENT, "payload", Map.of("values", values),
                            "workerSelector", Map.of("executorName", FUNCTION, "input", Map.of())));
                }
                items.add(Map.of("messageId", "unobserved-event", "eventCode", OTHER_EVENT, "payload", Map.of("value", "abc"),
                        "workerSelector", Map.of("executorName", FUNCTION, "input", Map.of())));
                call(http, base, "POST", "/api/v1/tasks/" + task + "/items", items);
                call(http, base, "POST", "/api/v1/tasks/" + task + "/approve", Map.of("expectedItemCount", 4));
                await(() -> {
                    if (hostFailure.get() != null) throw new AssertionError("Proof Host failed", hostFailure.get());
                    return "terminal".equals(call(http, base, "GET", "/api/v1/projects/default/tasks/" + task, null).get("scoreBand"));
                });
                var ids = items.stream().map(item -> (String) item.get("messageId")).toList();
                var results = call(http, base, "POST", "/api/v1/tasks/" + task + "/results:load", ids);
                var states = call(http, base, "POST", "/api/v1/tasks/" + task + "/items:states", ids);
                for (String id : ids) {
                    assertThat((Map<String,Object>) states.get(id)).containsEntry("band", "terminal");
                    var result = (Map<String,Object>) results.get(id);
                    assertThat(result).containsEntry("status", "succeeded");
                    var payload = Jsons.parseObject((String) result.get("opaqueResultPayload"));
                    var answers = id.equals("unobserved-event") ? List.of(payload) : (List<Map<String,Object>>) payload.get("results");
                    assertThat(answers).extracting(answer -> answer.get("input"))
                            .containsExactlyElementsOf(id.equals("unobserved-event") ? List.of("abc") : expected.get(id));
                    for (var answer : answers) assertThat(answer).containsEntry("md5", HexFormat.of().formatHex(
                            MessageDigest.getInstance("MD5").digest(((String) answer.get("input")).getBytes(StandardCharsets.UTF_8))));
                }
                await(() -> {
                    var selected = observations.snapshot().stream().filter(row -> row.workerGroupId().equals(GROUP) && row.messageEventName().equals(EVENT)).toList();
                    if (selected.size() != 3) return false;
                    String worker = selected.getFirst().workerIds().getFirst();
                    long last = selected.stream().mapToLong(row -> row.observedAtMillis()).max().orElseThrow();
                    long count = selected.stream().filter(row -> row.observedAtMillis() / 120000 == last / 120000).count();
                    var properties = context.getBean(WorkerProperties.class).loadWorkerFacts(GROUP, List.of(worker)).get(worker).platformProperties();
                    return properties.get("proofObservedAt") instanceof Number at && at.longValue() == last
                            && properties.get("proofObservedCount") instanceof Number value && value.longValue() == count;
                });
                if (scenario) {
                    assertThat(call(http, base, "GET", "/api/v1/app-checks/configuration", null).toString()).doesNotContain("poolGroups", "newScopeRequired");
                    call(http, base, "POST", "/api/v1/users/proof/credits/adjustments", Map.of(
                            "requestId", UUID.randomUUID().toString(), "delta", 100, "reason", "Group binding proof fixture"));
                    String business = (String) call(http, base, "POST", "/api/v1/app-checks/tasks", Map.of("requestId", "business-group-c", "checkId", "group-c-md5")).get("taskId");
                    var imported = send(http, base, "POST", "/api/v1/app-checks/tasks/" + business + "/seeds:import", String.join("\n", seeds), "text/plain");
                    assertThat(Jsons.parseObject(imported.body())).containsEntry("itemCount", 3L).containsEntry("uniqueCount", 23L);
                    var quote = call(http, base, "GET", "/api/v1/app-checks/tasks/" + business + "/credits", null);
                    assertThat(quote).containsEntry("seedCount", 23L).containsEntry("amount", 23L);
                    call(http, base, "POST", "/api/v1/tasks/" + business + "/approve", Map.of("expectedItemCount", 3,
                            "credits", Map.of("requestId", UUID.randomUUID().toString(),
                                    "expectedSeedCount", quote.get("seedCount"), "expectedAmount", quote.get("amount"))));
                    await(() -> "terminal".equals(call(http, base, "GET", "/api/v1/projects/app-checks/tasks/" + business, null).get("scoreBand")));
                    var rows = (List<Map<String,Object>>) call(http, base, "GET", "/api/v1/app-checks/tasks/" + business + "/results", null).get("results");
                    assertThat(rows).hasSize(23).allSatisfy(row -> assertThat(row).containsKey("matched").doesNotContainKey("contentError"));
                    var csv = send(http, base, "POST", "/api/v1/app-checks/tasks/" + business + "/results:export?filter=all", null, "application/json");
                    assertThat(csv.headers().firstValue("X-Export-Count")).contains("23");
                }
                assertThat(hostFailure.get()).isNull();
            } finally { host.interrupt(); host.join(10000); assertThat(host.isAlive()).isFalse(); }
        } finally {
            var client = RedisClient.create(redis);
            try (var connection = client.connect()) {
                ScanCursor cursor = ScanCursor.INITIAL;
                do { var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                    if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
                } while (!cursor.isFinished());
            } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); }
        }
    }

    private static List<Map<String,Object>> supply() { return List.of(Map.of("poolName", POOL, "target", Map.of(), "count", 100)); }
    private static Map<String,Object> call(HttpClient client, URI base, String method, String path, Object body) throws Exception {
        return Jsons.parseObject(send(client, base, method, path, body == null ? null : Jsons.toJson(body), "application/json").body());
    }
    private static HttpResponse<String> send(HttpClient client, URI base, String method, String path, String body, String type) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("X-XA-Dev-User", Base64.getUrlEncoder().withoutPadding().encodeToString("proof".getBytes(StandardCharsets.UTF_8)))
                .header("X-XA-Dev-Role", "admin").header("Content-Type", type)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        return response;
    }
    private static void await(Check condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do { if (condition.test()) return; Thread.sleep(50); } while (System.nanoTime() < end);
        throw new AssertionError("Group binding execution did not converge within its existing proof bound");
    }
    private interface Check { boolean test() throws Exception; }
    private static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
}
