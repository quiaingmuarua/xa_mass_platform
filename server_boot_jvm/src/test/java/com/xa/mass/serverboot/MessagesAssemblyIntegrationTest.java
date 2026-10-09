package com.xa.mass.serverboot;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.scenario.messages.MessageCampaignsScenarioConfiguration;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workersimulator.messaging.MessageScenario;
import io.lettuce.core.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Module-only assembly proof, separate from the all-products Preview profile. */
@Tag("scenario-composition")
@SuppressWarnings("unchecked")
class MessagesAssemblyIntegrationTest {
    private static final String EVENT = "extension.worker.message.send";

    @Test @Timeout(90)
    void messagesAloneExecutesAndContinuesTrackedResponsesAfterTaskTermination() throws Exception {
        try (var fixture = new Fixture(); var channel = new MessageScenario();
             var lab = new MessageFailureIntegrationTest.LabHttp(channel)) {
            fixture.start(true, Map.of("xa.mass.kernel-pacer.enabled", "true"));
            assertThat(fixture.get("/api/v1/messages/catalog").statusCode()).isEqualTo(200);
            assertThat(fixture.get("/api/v1/sms/catalog").statusCode()).isEqualTo(404);
            assertThat(fixture.get("/api/v1/app-checks/catalog").statusCode()).isEqualTo(404);
            assertThat(fixture.context.getBean(ProjectDirectory.class).projects()).containsOnlyKeys("messages");
            channel.hold(true);
            var manager = new AtomicReference<JavaWorkerManager>();
            var sender = channel.addSender("demo-sim", "one", () -> Map.of("phone", "+861700000000", "country", "CN"),
                    () -> manager.get().snapshot("one").workerId(), () -> "RUNNING");
            try (var worker = JavaWorkerManager.builder(fixture.base, "demo-sim", WorkerTransportType.WEBSOCKET)
                    .replica("one", () -> Map.of("phone", "+861700000000", "country", "CN", "messaging.enabled", "true"),
                            channel.definitions(sender)).build()) {
                manager.set(worker); worker.start();
                var created = fixture.post("/api/v1/messages/tasks", Map.of("requestId", "standalone", "name", "standalone",
                        "recipientCountry", "CN", "senderCountry", "CN", "body", "{}", "recipientIds", List.of("+8613800000000")));
                assertThat(created.statusCode()).isEqualTo(201);
                String task = (String) Jsons.parseObject(created.body()).get("taskId");
                await(() -> "terminal".equals(((Map<?, ?>) fixture.detail(task).get("task")).get("state")));
                await(() -> fixture.hasStatus(task, "SENT", null));
                var local = (Map<String, Object>) ((List<?>) channel.page(0, 1).get("items")).getFirst();
                String message = (String) local.get("messageId");
                String receipt = (String) ((Map<?, ?>) ((List<?>) local.get("receipts")).getFirst()).get("receiptId");
                channel.hold(false); channel.release(List.of(receipt));
                await(() -> fixture.hasStatus(task, "DELIVERED", null));
                channel.act(sender, message, "read", Map.of());
                await(() -> fixture.hasStatus(task, "READ", null));
                for (String reply : List.of("first", "latest")) {
                    channel.act(sender, message, "reply", Map.of("requestId", reply, "text", reply));
                    await(() -> fixture.hasStatus(task, "REPLIED", reply));
                }
                assertThat((Map<String, Object>) fixture.detail(task).get("task"))
                        .containsEntry("state", "terminal").containsEntry("repliedCount", 1L);
            }
        }
    }

    @Test @Timeout(90)
    void moduleBindsAPreRegisteredGroupAndRestartPreservesManagedTaskAndGroupData() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.start(false, Map.of());
            fixture.context.getBean(WorkerGroupRegistrationService.class).register("demo-sim", Map.of("owner", "external"), List.of(EVENT, "extra"));
            var before = fixture.context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("demo-sim")).get("demo-sim");
            fixture.restart(true, Map.of("xa.mass.worker-assembly.group-config-json", "{}"));
            String id = fixture.context.getBean(ProjectDirectory.class).requireManagedTaskId("messages", "demo-sim");
            var tasks = fixture.context.getBean(TaskResourceCatalog.class);
            var descriptor = tasks.loadTaskAllocationDescriptors(List.of(id)).get(id);
            var order = tasks.getProjectTask("messages", id);
            assertThat(order).isNotNull();
            fixture.restart(true, Map.of("xa.mass.worker-assembly.group-config-json", "{}"));
            assertThat(fixture.context.getBean(ProjectDirectory.class).requireManagedTaskId("messages", "demo-sim")).isEqualTo(id);
            tasks = fixture.context.getBean(TaskResourceCatalog.class);
            assertThat(tasks.loadTaskAllocationDescriptors(List.of(id)).get(id)).isEqualTo(descriptor);
            assertThat(tasks.getProjectTask("messages", id)).isEqualTo(order);
            assertThat(tasks.listProjectTasks("messages", 100).tasks()).containsExactly(order);
            assertThat(fixture.context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("demo-sim")).get("demo-sim"))
                    .isEqualTo(before);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"group", "event", "pool", "function"}) @Timeout(60)
    void missingResourcesStopStartupBeforeProjectProvisioning(String missing) throws Exception {
        try (var fixture = new Fixture()) {
            var overrides = new HashMap<String, String>();
            switch (missing) {
                case "group" -> overrides.put("xa.mass.worker-assembly.group-config-json", "{}");
                case "event" -> overrides.put("xa.mass.worker-assembly.group-config-json", Fixture.group("other-event"));
                case "pool" -> {
                    overrides.put("xa.mass.worker-matching.groups.demo-sim.pools[0]", "any");
                    overrides.put("xa.mass.worker-matching.groups.demo-sim.functions[0]", "worker.any");
                }
                case "function" -> overrides.put("xa.mass.worker-matching.groups.demo-sim.functions[1]", "worker.any");
            }
            assertThatThrownBy(() -> fixture.start(true, overrides)).hasStackTraceContaining("project=messages group=demo-sim");
            assertThatThrownBy(() -> { try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", fixture.adapter), 500); } })
                    .isInstanceOf(java.io.IOException.class);
            fixture.start(false, Map.of());
            assertThat(fixture.context.getBean(TaskResourceCatalog.class).listProjectTasks("messages", 100).tasks()).isEmpty();
            var group = fixture.context.getBean(WorkerResourceCatalog.class).getWorkerGroupDescriptors(List.of("demo-sim")).get("demo-sim");
            if (missing.equals("group")) assertThat(group).isNull();
            else assertThat(group).isNotNull(); // Host registration is create-only and not rolled back.
        }
    }

    private static void await(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            if (check.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Messages module observation timed out");
    }

    private static final class Fixture implements AutoCloseable {
        final String scope = "test_messages_assembly_" + UUID.randomUUID().toString().replace("-", "");
        final String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        final int port = port(), adapter = port();
        final URI base = URI.create("http://127.0.0.1:" + port);
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ConfigurableApplicationContext context;

        Fixture() throws Exception { }
        void start(boolean messages, Map<String, String> overrides) {
            var application = messages ? new SpringApplication(ServerBootConfiguration.class, MessageCampaignsScenarioConfiguration.class)
                    : new SpringApplication(ServerBootConfiguration.class);
            application.setRegisterShutdownHook(false);
            var settings = new LinkedHashMap<String, String>();
            settings.put("spring.profiles.active", "default"); settings.put("server.port", Integer.toString(port));
            settings.put("xa.mass.redis.url", redisUrl); settings.put("xa.mass.redis.scope", scope);
            settings.put("xa.mass.kernel-pacer.enabled", "false"); settings.put("logging.level.root", "ERROR");
            if (messages) {
                settings.put("xa.mass.scenarios.messages.worker-group-id", "demo-sim");
                settings.put("xa.mass.worker-assembly.group-config-json", group(EVENT));
                settings.put("xa.mass.worker-matching.groups.demo-sim.pools[0]", "messaging");
                settings.put("xa.mass.worker-matching.groups.demo-sim.pools[1]", "any");
                settings.put("xa.mass.worker-matching.groups.demo-sim.functions[0]", "worker.messaging.available");
                settings.put("xa.mass.worker-matching.groups.demo-sim.functions[1]", "worker.messaging.phone");
                settings.put("xa.mass.worker-matching.groups.demo-sim.functions[2]", "worker.any");
                settings.put("xa.mass.worker-delivery.adapter.remote-base-url", base.toString());
                settings.put("xa.mass.worker-delivery.adapter.instances.messages-test.type", "WEBSOCKET");
                settings.put("xa.mass.worker-delivery.adapter.instances.messages-test.listen-host", "127.0.0.1");
                settings.put("xa.mass.worker-delivery.adapter.instances.messages-test.listen-port", Integer.toString(adapter));
                Map.ofEntries(
                        Map.entry("command-backoff-min", "10ms"), Map.entry("command-backoff-step", "10ms"),
                        Map.entry("command-backoff-max", "100ms"), Map.entry("command-consume-limit", "500"),
                        Map.entry("command-retry-capacity", "1000"), Map.entry("report-backoff", "100ms"),
                        Map.entry("report-queue-capacity", "10000"), Map.entry("reconnect-verification-retention", "10m"),
                        Map.entry("maximum-disconnected-workers", "10000"), Map.entry("maximum-encoded-properties-bytes", "67108864"),
                        Map.entry("send-time-limit", "5s"), Map.entry("shutdown-timeout", "5s"))
                        .forEach((key, value) -> settings.put("xa.mass.worker-delivery.adapter.instances.messages-test." + key, value));
                settings.put("xa.mass.worker-endpoints.defaults.WEBSOCKET", "messages-test");
                settings.put("xa.mass.worker-endpoints.endpoints.messages-test.transport-type", "WEBSOCKET");
                settings.put("xa.mass.worker-endpoints.endpoints.messages-test.public-uri", "ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket");
            }
            settings.putAll(overrides);
            context = application.run(settings.entrySet().stream().map(row -> "--" + row.getKey() + "=" + row.getValue()).toArray(String[]::new));
        }
        void restart(boolean messages, Map<String, String> overrides) { context.close(); context = null; start(messages, overrides); }
        HttpResponse<String> get(String path) {
            try { return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        HttpResponse<String> post(String path, Object input) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(input))).build(), HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> detail(String id) { return Jsons.parseObject(get("/api/v1/messages/tasks/" + id).body()); }
        boolean hasStatus(String task, String status, String reply) {
            return ((List<Map<String, Object>>) detail(task).get("results")).stream()
                    .anyMatch(row -> status.equals(row.get("status")) && (reply == null || reply.equals(row.get("reply"))));
        }
        static String group(String event) {
            return Jsons.toJson(Map.of("demo-sim", Map.of("attributes", Map.of("owner", "host"), "eventCodes", List.of(event, "extra"))));
        }
        static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
        @Override public void close() {
            if (context != null) context.close();
            http.close();
            var client = RedisClient.create(redisUrl);
            try (var connection = client.connect()) {
                var redis = connection.sync(); ScanCursor cursor = ScanCursor.INITIAL;
                do {
                    var page = redis.scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                    if (!page.getKeys().isEmpty()) redis.unlink(page.getKeys().toArray(String[]::new));
                    cursor = page;
                } while (!cursor.isFinished());
            } finally { client.shutdown(); }
        }
    }
}
