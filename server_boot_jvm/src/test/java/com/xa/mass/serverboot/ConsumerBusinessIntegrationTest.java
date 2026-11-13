package com.xa.mass.serverboot;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.scenario.appchecks.AppCheckTaskService;
import com.xa.mass.scenario.messages.MessageTaskService;
import com.xa.mass.scenario.sms.SmsReceptionService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.observation.TaskLeaseProjection;
import com.xa.mass.server.task.observation.TaskLeaseProjectionService;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

/** Actual consumer defaults, external configuration and one Worker serving both finite businesses. */
@Tag("scenario-composition")
class ConsumerBusinessIntegrationTest {
    private static final String GROUP = "example-workers", MD5 = "extension.worker.string.md5";
    private static final String SEND = "extension.worker.message.send", PHONE = "+861700000001";
    private static final Map<String, String> GOLDENS = Map.of("a", "0cc175b9c0f1b6a831c399e269772661",
            "abc", "900150983cd24fb0d6963f7d28e17f72", "中文", "a7bac2239fcdcb3a067903d8077c4a07");
    @TempDir Path root;
    private final String scope = "test_consumer_business_" + UUID.randomUUID().toString().replace("-", "");
    private final String redis = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private int adapter;
    private URI base;

    @Test @Timeout(150) @SuppressWarnings("unchecked")
    void consumerStartsWithoutWorkersAndBothFiniteBusinessesExecuteAndSurviveRestart() throws Exception {
        writeConfiguration();
        String checks, messages, configuration, csv, messageJsonl;
        var checkRequest = Map.of("requestId", "consumer-checks", "checkId", "example-md5-prefix");
        var messageRequest = Map.of("requestId", "consumer-messages", "appId", "example-messages", "name", "External messages",
                "recipientCountry", "CN", "senderCountry", "CN", "body", "  local message 中文\n{}  ");
        String chargeRequestId = UUID.randomUUID().toString();
        var approval = Map.of("expectedItemCount", 3, "credits", Map.of("requestId", chargeRequestId, "expectedSeedCount", 3, "expectedAmount", 3));
        try (var server = start()) {
            assertThat(server.getBean(AppCheckTaskService.class).isRunning()).isTrue();
            assertThat(server.getBean(MessageTaskService.class).isRunning()).isTrue();
            assertNoSms(server);
            assertThat(server.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("default", "app-checks", "messages");
            var workers = server.getBean(WorkerResourceCatalog.class);
            assertThat(workers.sampleWorkerGroupDescriptors(10)).containsOnlyKeys(GROUP);
            assertThat(workers.sampleWorkerDescriptors(GROUP, 1)).isEmpty();
            assertThat(server.getBean(TaskScoreBandCore.class).previewScoreStates(1)).isEmpty();
            assertThat((List<?>) call("GET", "/api/v1/app-checks/catalog", null).get("checks")).hasSize(1);
            assertThat((List<?>) call("GET", "/api/v1/messages/catalog", null).get("applications")).hasSize(1);

            checks = (String) call("POST", "/api/v1/app-checks/tasks", checkRequest).get("taskId");
            messages = (String) call("POST", "/api/v1/messages/tasks", messageRequest).get("taskId");
            var imported = text("/api/v1/app-checks/tasks/" + checks + "/seeds:import", "a\nabc\n中文");
            assertThat(Jsons.parseObject(imported.body())).containsEntry("itemCount", 3L).containsEntry("addedCount", 3L);
            assertThat(Jsons.parseObject(text("/api/v1/app-checks/tasks/" + checks + "/seeds:import", "a\nabc\n中文").body()))
                    .containsEntry("addedCount", 0L).containsEntry("existingCount", 3L);
            assertThat(Jsons.parseObject(text("/api/v1/messages/tasks/" + messages + "/recipients:import", "+8613800000000").body()))
                    .containsEntry("confirmedAddedCount", 1L);
            for (var pair : Map.of("app-checks", checks, "messages", messages).entrySet()) {
                var detail = call("GET", "/api/v1/projects/" + pair.getKey() + "/tasks/" + pair.getValue(), null);
                assertThat(detail).containsEntry("scoreBand", "pre_review");
                assertThat((Map<String,Object>) detail.get("task")).containsEntry("workerGroupId", GROUP);
            }
            assertThat(call("GET", "/api/v1/app-checks/tasks/" + checks + "/credits", null))
                    .containsEntry("unitPrice", 1L).containsEntry("seedCount", 3L).containsEntry("amount", 3L);
            call("POST", "/api/v1/users/1000/credits/adjustments", Map.of("requestId", UUID.randomUUID().toString(), "delta", 20, "reason", "Local consumer proof"));

            var manager = new AtomicReference<JavaWorkerManager>();
            var reporter = new AtomicReference<WorkerOutcomeReporter>();
            var accepted = new AtomicReference<Map<String,Object>>();
            var digest = WorkerEventDefinition.extension("string.md5", WorkerEventParameterResolvers.jsonMap(), input ->
                    Jsons.toJson(Map.of("valid", true, "input", input.get("value"), "md5", md5((String) input.get("value")),
                            "workerId", manager.get().snapshot("one").workerId())));
            var send = WorkerEventDefinition.extension("message.send", WorkerEventParameterResolvers.jsonMap(), (input, outcome) -> {
                var snapshot = new LinkedHashMap<String,Object>(input);
                snapshot.put("workerId", manager.get().snapshot("one").workerId()); snapshot.put("phone", PHONE);
                snapshot.put("status", "SENT"); snapshot.put("observedAtMillis", System.currentTimeMillis());
                accepted.set(Map.copyOf(snapshot)); reporter.set(outcome);
                return Jsons.toJson(snapshot);
            });
            try (var worker = JavaWorkerManager.builder(base, GROUP, WorkerTransportType.WEBSOCKET)
                    .replica("one", () -> Map.of("country", "CN", "phone", PHONE, "messaging.enabled", "true"), List.of(digest, send)).build()) {
                manager.set(worker);
                call("POST", "/api/v1/tasks/" + checks + "/approve", approval);
                call("POST", "/api/v1/tasks/" + checks + "/approve", approval);
                worker.start();
                call("POST", "/api/v1/tasks/" + messages + "/approve", Map.of("expectedItemCount", 1));
                await(() -> terminal("app-checks", checks) && terminal("messages", messages));
                assertThat(call("GET", "/api/v1/users/1000/credits", null)).containsEntry("balance", 17L);
                assertCheckResults(checks, worker.snapshot("one").workerId());
                assertThat(messageResult(messages)).containsEntry("status", "SENT").containsEntry("body", messageRequest.get("body"))
                        .containsEntry("workerId", worker.snapshot("one").workerId());
                assertRawInput(messages, SEND, "worker.messaging.available", Map.of("country", List.of("CN")), 1);
                for (String status : List.of("READ", "REPLIED")) {
                    var next = new LinkedHashMap<>(accepted.get());
                    next.put("status", status); next.put("observedAtMillis", System.currentTimeMillis());
                    if (status.equals("REPLIED")) { next.put("reply", "local reply"); next.put("replyRequestId", "consumer-reply"); }
                    assertThat(reporter.get().report(status.equals("READ") ? 8 : 9, System.currentTimeMillis(), Jsons.toJson(next))).isTrue();
                    await(() -> status.equals(messageResult(messages).get("status")));
                    assertThat(terminal("messages", messages)).isTrue();
                }
                assertThat(messageResult(messages)).containsEntry("reply", "local reply");
                csv = export("/api/v1/app-checks/tasks/" + checks + "/results:export").body();
                assertThat(csv.lines()).hasSize(4);
                for (String expected : GOLDENS.values()) assertThat(csv).contains(expected);
                messageJsonl = export("/api/v1/tasks/" + messages + "/results:export").body();
                assertThat(messageJsonl.lines()).hasSize(1);
                var exported = Jsons.parseObject(messageJsonl.strip());
                assertThat(Jsons.parseObject((String) exported.get("opaqueResultPayload")))
                        .containsEntry("status", "REPLIED").containsEntry("reply", "local reply");
            }
            configuration = Files.readString(root.resolve("data/configuration.json"));
        }
        try (var server = start()) {
            assertNoSms(server);
            assertThat(Files.readString(root.resolve("data/configuration.json"))).isEqualTo(configuration);
            assertThat(call("POST", "/api/v1/app-checks/tasks", checkRequest)).containsEntry("taskId", checks);
            assertThat(call("POST", "/api/v1/messages/tasks", messageRequest)).containsEntry("taskId", messages);
            assertThat(call("GET", "/api/v1/app-checks/tasks/" + checks + "/credits?requestId=" + chargeRequestId, null))
                    .containsEntry("status", "closed").containsEntry("requestStatus", "applied").containsEntry("amount", 3L);
            assertThat(call("GET", "/api/v1/users/1000/credits", null)).containsEntry("balance", 17L);
            assertThat(terminal("app-checks", checks)).isTrue();
            assertThat(terminal("messages", messages)).isTrue();
            assertThat(messageResult(messages)).containsEntry("status", "REPLIED").containsEntry("reply", "local reply");
            assertThat(export("/api/v1/app-checks/tasks/" + checks + "/results:export").body().lines())
                    .containsExactlyInAnyOrderElementsOf(csv.lines().toList());
            assertThat(export("/api/v1/tasks/" + messages + "/results:export").body()).isEqualTo(messageJsonl);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"checks", "path", "seed", "applications"}) @Timeout(30)
    void consumerDefaultsRequireExplicitBusinessConfigurationBeforeOpeningAdapter(String missing) throws Exception {
        var configuration = writeConfiguration();
        if (missing.equals("applications")) configuration.keySet().removeIf(name -> name.startsWith("xa.mass.scenarios.messages.applications"));
        else {
            if (missing.equals("checks") || missing.equals("path")) configuration.remove("xa.mass.scenarios.app-checks.configuration.path");
            if (missing.equals("checks") || missing.equals("seed")) configuration.remove("xa.mass.scenarios.app-checks.configuration.seed");
        }
        Files.writeString(root.resolve("Runtime 配置.yaml"), Jsons.toJson(configuration));
        assertThatThrownBy(() -> { try (var ignored = start()) { } })
                .hasStackTraceContaining(missing.equals("applications") ? "Messages applications must be explicitly configured" : "App Checks configuration");
        assertThatThrownBy(() -> {
            try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", adapter), 200); }
        }).isInstanceOf(java.io.IOException.class);
        if (!missing.equals("applications")) {
            assertThat(root.resolve("data/configuration.json")).doesNotExist();
            assertThat(root.resolve("data/configuration.json.lock")).doesNotExist();
        }
    }

    private Map<String,Object> writeConfiguration() throws Exception {
        int api;
        try (var socket = new ServerSocket(0); var adapterSocket = new ServerSocket(0)) {
            api = socket.getLocalPort(); adapter = adapterSocket.getLocalPort();
        }
        Path example = Path.of(System.getProperty("xa.mass.repository.root"))
                .resolve("distribution/server/src/main/dist/examples/consumer-business");
        Files.copy(example.resolve("batch-checks-configuration.json"), root.resolve("checks.json"));
        Files.copy(example.resolve("worker-groups.json"), root.resolve("Group 注册.json"));
        try (var users = new org.springframework.core.io.ClassPathResource("users/development-directory.json").getInputStream()) {
            Files.write(root.resolve("users.json"), users.readAllBytes());
        }
        var host = new LinkedHashMap<String,Object>();
        for (var source : new YamlPropertySourceLoader().load("consumer-example", new FileSystemResource(example.resolve("runtime.yaml")))) {
            var properties = (EnumerablePropertySource<?>) source;
            for (String name : properties.getPropertyNames()) host.put(name, properties.getProperty(name));
        }
        assertThat(host.keySet()).noneMatch(name -> name.startsWith("xa.mass.scenarios.") && name.endsWith(".enabled"));
        host.put("server.port", api); host.put("logging.level.root", "ERROR");
        host.put("xa.mass.users.seed", root.resolve("users.json").toUri().toString());
        host.put("xa.mass.redis.url", redis); host.put("xa.mass.redis.scope", scope);
        host.put("xa.mass.task-retention.enabled", false);
        host.put("xa.mass.scenarios.app-checks.configuration.path", root.resolve("data/configuration.json").toString());
        host.put("xa.mass.scenarios.app-checks.configuration.seed", root.resolve("checks.json").toUri().toString());
        host.put("xa.mass.worker-delivery.adapter.instances.runtime-websocket.listen-port", adapter);
        host.put("xa.mass.worker-endpoints.endpoints.runtime-websocket.public-uri", "ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket");
        // No Scenario enabled flags: this proves the actual consumer profile defaults.
        Files.writeString(root.resolve("Runtime 配置.yaml"), Jsons.toJson(host));
        return host;
    }

    private ConfigurableApplicationContext start() throws Exception {
        var application = new SpringApplication(ServerBootConfiguration.class);
        application.setRegisterShutdownHook(false);
        var server = application.run("--spring.profiles.active=consumer", "--spring.config.additional-location=" + root.resolve("Runtime 配置.yaml").toUri(),
                "--xa.mass.worker-assembly.group-config-json=" + Files.readString(root.resolve("Group 注册.json")));
        base = URI.create("http://127.0.0.1:" + server.getEnvironment().getRequiredProperty("local.server.port"));
        return server;
    }
    private void assertNoSms(ConfigurableApplicationContext server) throws Exception {
        assertThat(server.getBeansOfType(SmsReceptionService.class)).isEmpty();
        assertThat(server.getBeansOfType(TaskLeaseProjection.class)).isEmpty();
        assertThat(server.getBean(TaskLeaseProjectionService.class).enabled()).isFalse();
        assertThat(server.getBean(ProjectDirectory.class).projects()).doesNotContainKey("sms");
        assertThat(send("GET", "/api/v1/sms/catalog", null, "application/json").statusCode()).isEqualTo(404);
    }
    @SuppressWarnings("unchecked")
    private void assertCheckResults(String task, String workerId) throws Exception {
        var rows = assertRawInput(task, MD5, "worker.any", Map.of(), 3);
        var observed = new HashSet<String>();
        for (var row : rows) {
            var raw = (Map<String,Object>) row.get("result");
            assertThat(raw).containsEntry("status", "succeeded");
            var content = Jsons.parseObject((String) raw.get("opaqueResultPayload"));
            String seed = (String) content.get("input");
            assertThat(content).containsEntry("workerId", workerId).containsEntry("md5", GOLDENS.get(seed));
            observed.add(seed);
        }
        assertThat(observed).containsExactlyInAnyOrderElementsOf(GOLDENS.keySet());
        var answers = (List<Map<String,Object>>) call("GET", "/api/v1/app-checks/tasks/" + task + "/results", null).get("results");
        assertThat(answers).hasSize(3).allSatisfy(row -> {
            String seed = (String) row.get("seed");
            assertThat(row).doesNotContainKey("contentError").containsEntry("matched", GOLDENS.get(seed).startsWith("0"));
            assertThat((Map<String,Object>) row.get("details")).containsEntry("md5", GOLDENS.get(seed));
        });
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> assertRawInput(String task, String event, String query, Object input, int count) throws Exception {
        var rows = (List<Map<String,Object>>) call("GET", "/api/v1/tasks/" + task + "/results:preview", null).get("results");
        assertThat(rows).hasSize(count).allSatisfy(row -> {
            var item = (Map<String,Object>) row.get("item");
            assertThat(item).containsEntry("eventCode", event)
                    .containsEntry("workerSelector", Map.of("executorName", query, "input", input));
        });
        var ids = rows.stream().map(row -> (String) row.get("messageId")).toList();
        assertThat(ids).doesNotHaveDuplicates();
        var states = call("POST", "/api/v1/tasks/" + task + "/items:states", ids);
        assertThat(states.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(states.values()).allSatisfy(state -> assertThat((Map<String,Object>) state).containsEntry("band", "terminal"));
        return rows;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> messageResult(String task) throws Exception {
        var rows = (List<Map<String,Object>>) call("GET", "/api/v1/messages/tasks/" + task + "/results", null).get("results");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
    private boolean terminal(String project, String task) throws Exception {
        return "terminal".equals(call("GET", "/api/v1/projects/" + project + "/tasks/" + task, null).get("scoreBand"));
    }
    private Map<String,Object> call(String method, String path, Object body) throws Exception {
        var result = send(method, path, body == null ? null : Jsons.toJson(body), "application/json");
        assertThat(result.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        return Jsons.parseObject(result.body());
    }
    private HttpResponse<String> text(String path, String content) throws Exception {
        var result = send("POST", path, content, "text/plain; charset=utf-8");
        assertThat(result.statusCode()).as(path).isEqualTo(200); return result;
    }
    private HttpResponse<String> export(String path) throws Exception {
        var result = send("POST", path, null, "application/json");
        assertThat(result.statusCode()).as(path).isEqualTo(200); return result;
    }
    private HttpResponse<String> send(String method, String path, String body, String type) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer xak_dev_1000").header("Content-Type", type)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String md5(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        do { if (condition.test()) return; Thread.sleep(50); } while (System.nanoTime() < deadline);
        throw new AssertionError("Consumer business flow did not converge");
    }
    private interface Check { boolean test() throws Exception; }
    @AfterEach void cleanup() {
        http.close();
        var client = RedisClient.create(redis);
        try (var connection = client.connect()) {
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
            } while (!cursor.isFinished());
        } finally { client.shutdown(); }
    }
}
