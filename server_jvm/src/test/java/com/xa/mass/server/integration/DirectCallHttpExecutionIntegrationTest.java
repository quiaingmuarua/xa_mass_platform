package com.xa.mass.server.integration;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/Redis boundary; this finite caller impersonates an unstarted Adapter's HTTP side. */
@Tag("runtime-boundary")
class DirectCallHttpExecutionIntegrationTest {
    private static final String ADAPTER = "direct-http-boundary";
    private static final String GROUP = "direct-http-group";
    private static final String MESSAGE = "extension.worker.boundary";
    private static final String ROUTE = "/api/v1/worker-delivery/endpoint-managers/" + ADAPTER;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir Path temporary;

    @ParameterizedTest(name = "virtual={0}, min-spare={1}")
    @CsvSource({"false,10", "false,200", "true,10"})
    void executionConfigurationsPreserveDirectOutcomesAndSingleHttpCompletion(boolean virtual, int minimum)
            throws Exception {
        var scope = RedisTestScope.create("direct_http_execution");
        try (var context = new SpringApplicationBuilder(XaMassServerApplication.class).profiles("test").run(
                "--server.port=0", "--xa.mass.redis.url=" + REDIS_URL, "--xa.mass.redis.scope=" + scope.scope(),
                "--xa.mass.kernel-pacer.enabled=false", "--xa.mass.diagnostics.enabled=true",
                "--spring.threads.virtual.enabled=" + virtual, "--server.tomcat.threads.max=200",
                "--server.tomcat.threads.min-spare=" + minimum,
                "--xa.mass.worker-endpoints.defaults.WEBSOCKET=" + ADAPTER,
                "--xa.mass.worker-endpoints.endpoints." + ADAPTER + ".transport-type=WEBSOCKET",
                "--xa.mass.worker-endpoints.endpoints." + ADAPTER + ".public-uri=ws://127.0.0.1:1/boundary");
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
             var recording = new Recording()) {
            String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var http = new BoundaryHttp(client, base);
            assertThat(http.post("/api/v1/worker-groups/" + GROUP + ":register", "{\"eventCodes\":[]}")
                    .statusCode()).isEqualTo(200);
            var prepared = http.post("/api/v1/worker-groups/" + GROUP + "/workers:prepare",
                    "{\"transportType\":\"WEBSOCKET\",\"workerProperties\":{\"clientWorkerKey\":\"boundary\"}}");
            assertThat(prepared.statusCode()).isEqualTo(200);
            String worker = JSON.readTree(prepared.body()).get("workerId").asText();
            recording.enable("xa.mass.HttpInitial");
            recording.enable("xa.mass.HttpCompletion");
            recording.enable("xa.mass.HttpExecutor").withPeriod(Duration.ofMillis(100));
            recording.start();

            // Arrange an occupied real Owner slot. HTTP rejection must not replace that command.
            var occupied = DeliveryCommand.create(DeliveryEndpoint.SERVER, DeliveryEndpoint.WORKER, MESSAGE,
                    System.currentTimeMillis() + 30_000, "fixture", "occupied-fixture");
            assertThat(context.getBean(WorkerCommandRuntime.class).offerWorkerCommands(ADAPTER, Map.of(worker, occupied))
                    .get(worker)).isEqualTo(WorkerCommandRuntime.WorkerCommandOfferStatus.OFFERED);
            assertReason(http.call(worker, 10_000).get(5, TimeUnit.SECONDS), worker, "rejected", "command-slot-occupied");
            assertThat(http.consume(worker).get("forward").asText()).isEqualTo("occupied-fixture");

            var successful = http.call(worker, 10_000);
            JsonNode successCommand = http.consume(worker);
            assertCounts(http.report(worker, successCommand), 1, 0);
            assertObserved(successful.get(5, TimeUnit.SECONDS), worker);

            var timedOut = http.call(worker, 1_000);
            JsonNode lateCommand = http.consume(worker);
            assertReason(timedOut.get(5, TimeUnit.SECONDS), worker, "unobserved", "timeout");
            assertCounts(http.report(worker, lateCommand), 0, 1);

            var following = http.call(worker, 10_000);
            JsonNode followingCommand = http.consume(worker);
            assertCounts(http.report(worker, lateCommand), 0, 1);
            assertThat(following.isDone()).isFalse();
            assertCounts(http.report(worker, followingCommand), 1, 0);
            assertObserved(following.get(5, TimeUnit.SECONDS), worker);

            var closing = http.call(worker, 10_000);
            http.consume(worker); // Admission is established before closing the request Owner.
            context.getBean(DirectCallRegistry.class).close();
            assertReason(closing.get(5, TimeUnit.SECONDS), worker, "unobserved", "shutdown");
            assertThat(http.call(worker, 10_000).get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(503);

            List<RecordedEvent> events = List.of();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            Path path = temporary.resolve("http-" + virtual + "-" + minimum + ".jfr");
            do {
                recording.dump(path);
                events = RecordingFile.readAllEvents(path);
                if (directEvents(events, "xa.mass.HttpCompletion").size() == 6
                        && events.stream().anyMatch(e -> e.getEventType().getName().equals("xa.mass.HttpExecutor"))) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            var initial = directEvents(events, "xa.mass.HttpInitial");
            assertThat(initial).hasSize(6).allMatch(e -> e.getBoolean("virtualThread") == virtual);
            var completed = directEvents(events, "xa.mass.HttpCompletion");
            assertThat(completed).hasSize(6);
            assertThat(completed.stream().map(e -> e.getInt("httpStatus"))).containsExactlyInAnyOrder(200, 200, 200, 200, 200, 503);
            var executor = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpExecutor"))
                    .findFirst().orElseThrow();
            assertThat(executor.getBoolean("platformPool")).isEqualTo(!virtual);
            assertThat(executor.getInt("maximum")).isEqualTo(virtual ? -1 : 200);
            assertThat(executor.getInt("minimum")).isEqualTo(virtual ? -1 : minimum);
        } finally {
            RedisClient redis = RedisClient.create(REDIS_URL);
            try (var connection = redis.connect(StringCodec.UTF8)) { scope.cleanup(connection.sync()); }
            finally { redis.shutdown(); }
        }
    }

    private static List<RecordedEvent> directEvents(List<RecordedEvent> events, String type) {
        return events.stream().filter(e -> e.getEventType().getName().equals(type)
                && e.getString("operation").equals("DIRECT_CALL")).toList();
    }

    private static void assertReason(HttpResponse<String> response, String worker, String status, String reason) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode target = JSON.readTree(response.body()).get("results").get(worker);
        assertThat(target.get("status").asText()).isEqualTo(status);
        assertThat(target.get("reason").asText()).isEqualTo(reason);
        assertThat(target.has("opaqueResultPayload")).isFalse();
    }

    private static void assertObserved(HttpResponse<String> response, String worker) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode target = JSON.readTree(response.body()).get("results").get(worker);
        assertThat(target.get("status").asText()).isEqualTo("observed");
        assertThat(target.get("outcomeCode").asText()).isEqualTo("200");
        assertThat(target.get("opaqueResultPayload").asText()).isEqualTo("fixture-result");
    }

    private static void assertCounts(HttpResponse<String> response, int accepted, int rejected) {
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode counts = JSON.readTree(response.body());
        assertThat(counts.get("acceptedCount").asInt()).isEqualTo(accepted);
        assertThat(counts.get("rejectedCount").asInt()).isEqualTo(rejected);
    }

    private record BoundaryHttp(HttpClient client, String base) {
        CompletableFuture<HttpResponse<String>> call(String worker, int timeout) {
            return client.sendAsync(request(ROUTE + "/direct-calls", JSON.writeValueAsString(Map.of(
                    "workerGroupId", GROUP, "workerPayloads", Map.of(worker, "fixture"),
                    "messageType", MESSAGE, "waitTimeoutMillis", timeout))), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path, String body) throws Exception {
            return client.send(request(path, body), HttpResponse.BodyHandlers.ofString());
        }
        HttpRequest request(String path, String body) {
            // Cold application preparation is outside the bounded Direct HTTP interactions.
            return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(path.startsWith(ROUTE) ? 5 : 30))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }
        JsonNode consume(String worker) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            do {
                var response = post(ROUTE + "/commands:consume", "100");
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode command = JSON.readTree(response.body()).get(worker);
                if (command != null) return command;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("The admitted command was not consumed within the boundary budget");
        }
        HttpResponse<String> report(String worker, JsonNode command) throws Exception {
            return post(ROUTE + "/results:append", JSON.writeValueAsString(List.of(Map.of(
                    "src", "WORKER", "sourceId", worker, "dst", "SERVER", "messageType", MESSAGE,
                    "outcomeCode", "200", "payload", "fixture-result", "forward", command.get("forward").asText()))));
        }
    }
}
