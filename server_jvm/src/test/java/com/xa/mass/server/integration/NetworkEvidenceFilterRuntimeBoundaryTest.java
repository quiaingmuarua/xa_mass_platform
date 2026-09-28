package com.xa.mass.server.integration;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.testsupport.ServerTestConfiguration;
import com.xa.mass.workermatching.index.NetworkEvidenceTimestamps;
import com.xa.mass.workermatching.WorkerProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Finite Adapter HTTP witness: real admission, network queue, Matching and Score operations. */
@Tag("runtime-boundary")
class NetworkEvidenceFilterRuntimeBoundaryTest {
    private static final String ADAPTER = "history-adapter";
    private static final String GROUP = "network-history";
    private static final String BARRIER_GROUP = "network-barrier";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void validOlderEvidenceIsFilteredAcrossBatchesAndServerRestartWithoutFacts() throws Exception {
        var scope = RedisTestScope.create("network_history");
        var redis = RedisClient.create(REDIS_URL);
        try (var witness = redis.connect(); var http = HttpClient.newHttpClient()) {
            String worker;
            long connectedAt;
            long connectedScore;
            String timestampKey = NetworkEvidenceTimestamps.key(scope.keyspace(), GROUP);
            try (var context = start(scope)) {
                var api = new Boundary(context, http, witness.sync(), scope);
                api.register(GROUP);
                api.register(BARRIER_GROUP);
                worker = api.prepare(GROUP, "target");
                assertThat(context.getBean(WorkerProperties.class).loadWorkerFacts(GROUP, List.of(worker)).get(worker))
                        .isNull();
                // Keep the older evidence in the SAME Score time slot. Score's existing time
                // check alone would admit it, so the assertion exercises the new timestamp filter.
                long now = System.currentTimeMillis();
                connectedAt = now % 100 == 0 ? now - 1 : now - now % 100 + 1;
                api.deliverThenBarrier(connection(worker, "CONNECTED", connectedAt));
                connectedScore = api.score(worker);
                assertThat(connectedScore).isPositive();
                assertThat(witness.sync().hget(timestampKey, worker)).isEqualTo(Long.toString(connectedAt));

                assertFresh(connectedAt - 1);
                api.deliverThenBarrier(connection(worker, "DISCONNECTED", connectedAt - 1));
                assertThat(api.score(worker)).isEqualTo(connectedScore);
                api.deliverThenBarrier(probeMiss(worker, connectedAt - 1));
                assertThat(api.score(worker)).isEqualTo(connectedScore);
                assertThat(context.getBean(WorkerProperties.class).loadWorkerFacts(GROUP, List.of(worker)).get(worker))
                        .isNull();
            }

            // New Spring/Pacer/Matching instances, same scope; no reconstruction or source fixture.
            try (var context = start(scope)) {
                var api = new Boundary(context, http, witness.sync(), scope);
                assertFresh(connectedAt - 1);
                api.deliverThenBarrier(connection(worker, "DISCONNECTED", connectedAt - 1));
                api.deliverThenBarrier(probeMiss(worker, connectedAt - 1));
                assertThat(api.score(worker)).isEqualTo(connectedScore);
                assertThat(witness.sync().hget(timestampKey, worker)).isEqualTo(Long.toString(connectedAt));

                long newer = System.currentTimeMillis();
                api.deliverThenBarrier(probeMiss(worker, newer));
                assertThat(api.score(worker)).isNegative();
                assertThat(witness.sync().hget(timestampKey, worker)).isEqualTo(Long.toString(newer));

                // A broken auxiliary record cannot disable the existing network convergence path.
                witness.sync().hset(timestampKey, worker, "corrupt");
                api.deliverThenBarrier(connection(worker, "CONNECTED", System.currentTimeMillis()));
                assertThat(api.score(worker)).isPositive();
                assertThat(witness.sync().hget(timestampKey, worker)).isEqualTo("corrupt");
            }
        } finally {
            // All Server contexts are closed before this exact test scope is removed.
            try (var connection = redis.connect()) { scope.cleanup(connection.sync()); }
            finally { redis.shutdown(); }
        }
    }

    private static ConfigurableApplicationContext start(RedisTestScope scope) {
        return new SpringApplicationBuilder(ServerTestConfiguration.class).profiles("test").run(
                "--server.port=0", "--xa.mass.redis.url=" + REDIS_URL, "--xa.mass.redis.scope=" + scope.scope(),
                "--xa.mass.kernel-pacer.enabled=true", "--xa.mass.kernel-pacer.preset=DEFAULT",
                "--xa.mass.worker-endpoints.defaults.WEBSOCKET=" + ADAPTER,
                "--xa.mass.worker-endpoints.endpoints." + ADAPTER + ".transport-type=WEBSOCKET",
                "--xa.mass.worker-endpoints.endpoints." + ADAPTER + ".public-uri=ws://127.0.0.1:1/boundary");
    }

    private static Map<String, String> connection(String worker, String state, long at) {
        return report("platform.adapter.worker-connection.changed", JSON.writeValueAsString(
                Map.of("workerId", worker, "state", state, "observedAtMillis", at)),
                "worker-serviceability-evidence:v1");
    }

    private static Map<String, String> probeMiss(String worker, long at) {
        return report("platform.adapter.command.succeeded", JSON.writeValueAsString(
                Map.of("stateByWorkerId", Map.of(worker, "UNKNOWN"))), "worker-serviceability:v1:" + at);
    }

    private static Map<String, String> report(String event, String payload, String forward) {
        return Map.of("src", "ADAPTER", "sourceId", ADAPTER, "dst", "KERNEL", "messageType", event,
                "diagnosticCode", "", "payload", payload, "forward", forward);
    }

    private static void assertFresh(long observedAt) {
        assertThat(System.currentTimeMillis() - observedAt).isBetween(0L, 30_000L);
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(done.getAsBoolean()).isTrue();
    }

    private record Boundary(ConfigurableApplicationContext context, HttpClient http,
            RedisCommands<String, String> redis, RedisTestScope scope) {
        void register(String group) throws Exception {
            post("/api/v1/worker-groups/" + group + ":register", "{\"eventCodes\":[]}");
        }

        String prepare(String group, String key) throws Exception {
            return JSON.readTree(post("/api/v1/worker-groups/" + group + "/workers:prepare",
                    JSON.writeValueAsString(Map.of("transportType", "WEBSOCKET",
                            "workerProperties", Map.of("clientWorkerKey", key))))).get("workerId").asText();
        }

        void deliverThenBarrier(Map<String, String> observation) throws Exception {
            String barrier = prepare(BARRIER_GROUP, java.util.UUID.randomUUID().toString());
            post("/api/v1/worker-delivery/endpoint-managers/" + ADAPTER + "/results:append",
                    JSON.writeValueAsString(List.of(observation, connection(barrier, "CONNECTED", System.currentTimeMillis()))));
            // Group processing is ordered and network consumption is serial. A distinct fresh
            // Group/Worker reaching HOT proves the previous Group's Score call has completed.
            await(() -> rawScore(BARRIER_GROUP, barrier) > 0);
        }

        long score(String worker) { return rawScore(GROUP, worker); }

        private long rawScore(String group, String worker) {
            return redis.zscore(scope.keyspace().base() + ":worker:score:" + group, worker).longValue();
        }

        String post(String path, String body) throws Exception {
            String base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var response = http.send(HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path + " " + response.body())
                    .isEqualTo(path.endsWith("/results:append") ? 202 : 200);
            return response.body();
        }
    }
}
