package com.xa.mass.distribution;

import com.xa.mass.server.XaMassServerConfiguration;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.messages.backend.MessageProductConfiguration;
import com.xa.mass.workersimulator.messaging.MessageScenario;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Failure-only composition proof: real Redis, HTTP, Worker connection and recipient facts. */
@Tag("product-composition")
class MessageFailureIntegrationTest {
    @Test @Timeout(90)
    void newerBusinessReceiptSurvivesLateSynchronousExecutionResult() throws Exception {
        try (var fixture = new Fixture(false); var channel = new MessageScenario()) {
            var manager = new AtomicReference<JavaWorkerManager>();
            var executed = new CountDownLatch(1); var finishSend = new CountDownLatch(1);
            var sender = channel.addSender("demo-sim", "one", () -> Map.of("country", "CN", "phone", "+861700000000"), () -> manager.get().snapshot("one").workerId(), () -> "RUNNING");
            var handler = WorkerEventDefinition.extension("message.send", WorkerEventParameterResolvers.jsonMap(), (request, reporter) -> {
                // Hold the real synchronous handler completion; recipient actions still use its original Reporter.
                var sent = channel.send(sender, request, reporter); executed.countDown();
                if (!finishSend.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Send confirmation deadline");
                return Jsons.toJson(sent);
            });
            try (var worker = JavaWorkerManager.builder(fixture.base, "demo-sim", WorkerTransportType.WEBSOCKET)
                    .replica("one", () -> Map.of("phone", "+861700000000", "country", "CN", "messaging.enabled", "true"), List.of(handler)).build()) {
                manager.set(worker); worker.start();
                Map<String, Object> campaign = fixture.create(1);
                assertThat(executed.await(20, TimeUnit.SECONDS)).isTrue();
                var local = (Map<?, ?>) ((List<?>) channel.page(0, 1).get("items")).getFirst();
                String id = (String) local.get("messageId");
                assertThat(channel.act(id, "deliver", Map.of())).containsEntry("sendAccepted", true);
                assertThat(channel.act(id, "read", Map.of())).containsEntry("sendAccepted", true);
                assertThat(channel.act(id, "reply", Map.of("requestId", "reply", "text", "newer content"))).containsEntry("sendAccepted", true);
                var observed = fixture.awaitCampaign(campaign, "REPLIED");
                String task = (String) observed.get("taskId");
                assertThat(fixture.context.getBean(TaskDataService.class).loadTaskItemStates(task, List.of(id)).get(id).tag()).isEqualTo(9);
                finishSend.countDown();
                // A synchronous query on this same Worker can finish only after the send handler's Result was emitted.
                var direct = fixture.post("/api/v1/worker-delivery/endpoint-managers/products-websocket/direct-calls", Map.of(
                        "workerGroupId", "demo-sim", "workerPayloads", Map.of(worker.snapshot("one").workerId(), "null"),
                        "messageType", "platform.worker.events.snapshot", "waitTimeoutMillis", 5000));
                assertThat(direct.statusCode()).isEqualTo(200);
                assertThat(Jsons.parseObject(direct.body())).containsEntry("status", "observed");
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do {
                    var results = fixture.context.getBean(TaskDataService.class).loadTaskItemResults(task, List.of(id));
                    assertThat(Jsons.parseObject(results.get(id).opaqueResultPayload())).containsEntry("status", "REPLIED").containsEntry("reply", "newer content");
                    assertThat(fixture.context.getBean(TaskDataService.class).loadTaskItemStates(task, List.of(id)).get(id).tag()).isEqualTo(9);
                    Thread.sleep(50);
                } while (System.nanoTime() < until);
            } finally { finishSend.countDown(); }
        }
    }

    @Test @Timeout(60)
    void partialRealAppendRetainsTaskAndUnconfirmedSubmissionWithoutApproval() throws Exception {
        try (var fixture = new Fixture(true)) {
            var first = fixture.create(101);
            var unknown = fixture.awaitSubmission(first, "SUBMISSION_UNCONFIRMED");
            assertThat(unknown.get("taskId")).isNotNull();
            assertThat(fixture.create(101)).containsEntry("id", first.get("id")).containsEntry("taskId", unknown.get("taskId"));
            var service = fixture.context.getBean(TaskDataService.class);
            verify(service, times(2)).appendFiniteTaskItems(eq((String) unknown.get("taskId")), anyList());
            verify(fixture.context.getBean(TaskLifecycleService.class), never()).approve(anyString());
            var rows = (List<?>) fixture.get("/api/v1/messages/campaigns/" + first.get("id") + "/messages").get("items");
            assertThat(rows).allMatch(row -> "NOT_OBSERVED".equals(((Map<?, ?>) row).get("status")));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UncertainAppend {
        @Bean static BeanPostProcessor uncertainAppend() {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof TaskLifecycleService service) return spy(service);
                    if (!(bean instanceof TaskDataService service)) return bean;
                    var decorated = spy(service); var calls = new AtomicInteger();
                    doAnswer(call -> {
                        Object result = call.callRealMethod();
                        if (calls.incrementAndGet() == 2) throw new IllegalStateException("Append completed but its caller lost confirmation");
                        return result;
                    }).when(decorated).appendFiniteTaskItems(anyString(), anyList());
                    return decorated;
                }
            };
        }
    }

    static final class Fixture implements AutoCloseable {
        final String scope = "test_products_" + UUID.randomUUID().toString().replace("-", "");
        final String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final URI base;
        final ConfigurableApplicationContext context;
        Fixture(boolean failure) throws Exception {
            int port = port(), adapter = port(); base = URI.create("http://127.0.0.1:" + port);
            var classes = new ArrayList<Class<?>>(List.of(XaMassServerConfiguration.class, MessageProductConfiguration.class, ProductWorkerConfiguration.class));
            if (failure) classes.add(UncertainAppend.class);
            var app = new SpringApplication(classes.toArray(Class<?>[]::new)); app.setRegisterShutdownHook(false);
            context = app.run("--spring.profiles.active=product-preview,message-campaigns", "--server.port=" + port,
                    "--spring.config.additional-location=" + Path.of("../product-preview/config/application-product-preview.yaml").toAbsolutePath().normalize().toUri(),
                    "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                    "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                    "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                    "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket",
                    "--logging.level.root=ERROR");
        }
        Map<String, Object> create(int size) throws Exception {
            var result = post("/api/v1/messages/campaigns", Map.of("requestId", "request", "name", "proof", "country", "CN", "body", "message",
                    "recipientIds", IntStream.range(0, size).mapToObj(i -> "recipient-" + i).toList()));
            assertThat(result.statusCode()).isEqualTo(202); return Jsons.parseObject(result.body());
        }
        Map<String, Object> awaitSubmission(Map<String, Object> campaign, String expected) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Map<String, Object> value;
            do { value = get("/api/v1/messages/campaigns/" + campaign.get("id"));
                if (expected.equals(value.get("submission"))) return value;
                Thread.sleep(20);
            } while (System.nanoTime() < until);
            throw new AssertionError("Submission not observed");
        }
        Map<String, Object> awaitCampaign(Map<String, Object> campaign, String expected) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do { var value = get("/api/v1/messages/campaigns/" + campaign.get("id"));
                if (((Map<?, ?>) value.get("statuses")).containsKey(expected)) return value;
                Thread.sleep(20);
            } while (System.nanoTime() < until);
            throw new AssertionError("Receipt not observed");
        }
        HttpResponse<String> post(String path, Object body) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(6)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> get(String path) throws Exception {
            return Jsons.parseObject(http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body());
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
