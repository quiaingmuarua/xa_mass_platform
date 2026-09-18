package com.xa.mass.serverboot;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workersimulator.appchecks.AppRegistrationCheck;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP/Redis/Worker proof. The attempt witness exists only in this test assembly. */
@Tag("scenario-composition")
class AppChecksIntegrationTest {
    @Test @Timeout(150)
    void realHandlersIsolateAppsThrowFailuresAndRetainResultsAcrossServerRestart() throws Exception {
        try (var world = new World(); var a = world.workers("app-a-sim"); var b = world.workers("app-b-sim")) {
            a.start(); b.start();
            String registered = world.create("registered", "app-a", 101, 1000, 1000, 0);
            String unregistered = world.create("unregistered", "app-a", 4, 0, 1000, 0);
            String failed = world.create("failed", "app-b", 2, 0, 0, 0);
            String mixed = world.create("mixed", "app-b", 12, 500, 900, 10);
            var positive = world.settled(registered, 101);
            var negative = world.settled(unregistered, 4);
            var failures = world.settled(failed, 2);
            var mix = world.settled(mixed, 12);
            assertThat(task(positive)).containsEntry("succeededCount", 101L).containsEntry("failedCount", 0L);
            assertThat(rows(positive)).hasSizeLessThanOrEqualTo(100).isNotEmpty();
            assertThat(positive).containsEntry("resultsTruncated", true);
            assertThat(rows(positive)).allSatisfy(row -> assertThat(row).containsEntry("registered", true)
                    .containsEntry("workerGroupId", "app-a-sim"));
            assertThat(task(negative)).containsEntry("succeededCount", 4L).containsEntry("failedCount", 0L);
            assertThat(rows(negative)).allSatisfy(row -> assertThat(row).containsEntry("registered", false)
                    .containsEntry("resultStatus", "succeeded"));
            assertThat(task(failures)).containsEntry("failedCount", 2L).containsEntry("succeededCount", 0L);
            assertThat(rows(failures)).allSatisfy(row -> {
                assertThat(row).containsEntry("resultStatus", "failed").doesNotContainKey("registered");
                assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(row.get("number"))
                        && attempt.group().equals("app-b-sim") && attempt.failed());
            });
            for (var row : rows(mix)) {
                if (!"succeeded".equals(row.get("resultStatus"))) continue;
                assertThat(row).doesNotContainKey("contentError").containsEntry("workerGroupId", "app-b-sim");
                long bucket = hash("outcome", (String) row.get("workerId"), (String) task(mix).get("salt"), (String) row.get("number"));
                assertThat(bucket).isLessThan(900);
                assertThat(row.get("registered")).isEqualTo(bucket < 500);
            }
            // A delayed real Handler crosses the existing five-second claim. No single-execution assertion.
            String delayed = world.create("delayed", "app-a", 1, 1000, 1000, 6000);
            var late = world.settled(delayed, 1);
            assertThat(task(late)).containsEntry("succeededCount", 1L);
            assertThat(rows(late)).allSatisfy(row -> assertThat(row).containsEntry("simulatedDelayMillis", 6000L));
            assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(rows(late).getFirst().get("number"))
                    && !attempt.failed() && attempt.elapsedNanos() >= TimeUnit.SECONDS.toNanos(6));
            var original = task(negative);
            world.restart();
            var retained = world.get("/api/v1/app-checks/tasks/" + unregistered);
            assertThat(task(retained)).containsAllEntriesOf(original);
            assertThat(rows(retained)).containsExactlyInAnyOrderElementsOf(rows(negative));
            assertThat(world.get("/api/v1/app-checks/tasks?limit=100").get("tasks")).isInstanceOf(List.class);
        }
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

    static final class World implements AutoCloseable {
        final String scope = "test_app_checks_" + UUID.randomUUID().toString().replace("-", "");
        final String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        final URI base;
        final SpringApplication application = new SpringApplication(ServerBootConfiguration.class);
        final String[] arguments;
        ConfigurableApplicationContext context;
        int sequence;
        World() throws Exception {
            int port = port(), adapter = port(); base = URI.create("http://127.0.0.1:" + port);
            application.setRegisterShutdownHook(false);
            arguments = new String[]{"--spring.profiles.active=preview", "--server.port=" + port,
                    "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                    "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                    "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                    "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket",
                    "--logging.level.root=ERROR"};
            context = application.run(arguments);
        }
        JavaWorkerManager workers(String group) {
            var reference = new AtomicReference<JavaWorkerManager>();
            var builder = JavaWorkerManager.builder(base, group, WorkerTransportType.WEBSOCKET);
            for (String replica : List.of("one", "two")) {
                var actual = AppRegistrationCheck.definition(group, () -> reference.get().snapshot(replica).workerId());
                var witnessed = WorkerEventDefinition.extension("app.registration.check", WorkerEventParameterResolvers.jsonMap(), input -> {
                    long start = System.nanoTime(); boolean failed = false;
                    try { return actual.handler().execute(input); }
                    catch (WorkerException error) { failed = error.errorCode() == WorkerErrorCode.EVENT_EXECUTION_FAILED; throw error; }
                    finally {
                        if (attempts.size() >= 500) throw new AssertionError("Bounded attempt witness overflow");
                        attempts.add(new Attempt(group, (String) input.get("number"), failed, System.nanoTime() - start));
                    }
                });
                builder.replica(replica, () -> Map.of("simulated", "true"), List.of(witnessed));
            }
            var manager = builder.build(); reference.set(manager); return manager;
        }
        String create(String request, String app, int count, int registered, int unregistered, int delay) throws Exception {
            int offset = ++sequence * 1000;
            var body = Map.of("requestId", request, "name", request, "appId", app, "country", "CN",
                    "numbers", IntStream.range(offset, offset + count).mapToObj(i -> "+8613800" + String.format("%06d", i)).toList(),
                    "simulation", Map.of("ranges", Map.of("registered", List.of(0, registered), "unregistered", List.of(registered, unregistered),
                            "failed", List.of(unregistered, 1000)), "delayMs", List.of(delay, delay)));
            var response = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/app-checks/tasks"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            return (String) Jsons.parseObject(response.body()).get("taskId");
        }
        Map<String, Object> settled(String id, int total) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            do {
                var detail = get("/api/v1/app-checks/tasks/" + id); var task = task(detail);
                if (Long.valueOf(total).equals(task.get("totalCount")) && Long.valueOf(0).equals(task.get("activeCount"))
                        && "terminal".equals(task.get("state"))
                        && (long) task.get("succeededCount") + (long) task.get("failedCount") == total
                        && !rows(detail).isEmpty()) return detail;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Task did not close its finite execution observations");
        }
        Map<String, Object> get(String path) throws Exception {
            var response = http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); return Jsons.parseObject(response.body());
        }
        void restart() { context.close(); context = application.run(arguments); }
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
