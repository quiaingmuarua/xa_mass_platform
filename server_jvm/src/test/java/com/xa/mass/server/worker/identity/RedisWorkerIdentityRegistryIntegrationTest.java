package com.xa.mass.server.worker.identity;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.worker.endpoint.*;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory.Endpoint;
import com.xa.mass.server.worker.preparation.WorkerPreparationService;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerIdentityRegistryIntegrationTest {

    private RedisTestScope testScope;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisWorkerIdentityRegistry registry;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("worker_identity");
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        registry = new RedisWorkerIdentityRegistry(
                redisClient,
                testScope.keyspace()
        );
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            testScope.cleanup(connection.sync());
        }
        if (registry != null) {
            registry.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void concurrentRegistrationReturnsOneStableWorkerId() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int index = 0; index < 32; index++) {
                futures.add(executor.submit(() ->
                        registry.registerAll("group-1", List.of("client-key:14:installation-1")).getFirst()));
            }
            var workerIds = new HashSet<String>();
            for (Future<String> future : futures) {
                workerIds.add(future.get());
            }
            assertThat(workerIds).hasSize(1);
            String workerId = workerIds.iterator().next();
            assertThat(UUID.fromString(workerId).toString())
                    .isEqualTo(workerId);
            assertThat(connection.sync().hget(
                    testScope.keyspace().base()
                            + ":worker:identity:group-1",
                    "client-key:14:installation-1"
            )).isEqualTo(workerId);
        }
    }

    @Test
    void theSameClientKeyIsNamespacedByWorkerGroup() {
        String first = registry.registerAll("group-1", List.of("client-key:14:installation-1")).getFirst();
        String second = registry.registerAll("group-2", List.of("client-key:14:installation-1")).getFirst();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void typedRegistrationKeysShareTheGroupIdentityHash() {
        String clientKey = "client-key:14:installation-1";
        String scenarioKey = "scenario-lab:15:workers-a.jsonl:1";

        String clientWorker = registry.registerAll("group-1", List.of(clientKey)).getFirst();
        String workerSimulator = registry.registerAll("group-1", List.of(scenarioKey)).getFirst();

        assertThat(clientWorker).isNotEqualTo(workerSimulator);
        assertThat(connection.sync().hlen(
                testScope.keyspace().base()
                        + ":worker:identity:group-1"
        )).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void prepareAndCatalogAndBindingReadsMeetFourTwoOneCommandBudgets(int count) {
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        CommandListener listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try (var scores = new RedisWorkerScoreCore(redisClient, testScope.keyspace());
             var catalog = new RedisWorkerResourceCatalog(redisClient, scores, testScope.keyspace())) {
            catalog.registerWorkerGroup(new WorkerGroupDescriptor("g", Map.of(), Set.of()));
            // Establish all connections before measuring application commands.
            registry.registerAll("warmup", List.of("key"));
            scores.initializeRegisteredScores("warmup", List.of("w"));
            var directory = new WorkerEndpointDirectory(Map.of("system-polling",
                    new Endpoint(WorkerTransportType.POLLING,
                            URI.create("http://127.0.0.1:18082"))),
                    Map.of(WorkerTransportType.POLLING, "system-polling"));
            var preparation = new WorkerPreparationService(new WorkerIdentityService(registry), directory, catalog);
            var properties = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> Map.<String, Object>of("clientWorkerKey", "installation-" + i)).toList();
            commands.clear();
            try {
                var prepared = preparation.prepareAll("g", WorkerRegistrationKind.CLIENT_KEY,
                        WorkerTransportType.POLLING, properties);
                assertThat(commands).containsExactly("HMGET", "EVAL", "EVAL", "EVAL");
                var ids = prepared.stream().map(WorkerPreparationService.PreparedWorker::workerId).toList();
                commands.clear();
                assertThat(preparation.prepareAll("g", WorkerRegistrationKind.CLIENT_KEY,
                        WorkerTransportType.POLLING, properties)).isEqualTo(prepared);
                assertThat(commands).containsExactly("HMGET", "EVAL", "EVAL", "EVAL");
                commands.clear();
                catalog.registerWorkers("g", ids, "different-default");
                assertThat(commands).containsExactly("EVAL", "EVAL");
                commands.clear();
                assertThat(catalog.getWorkerDescriptors(ids)).hasSize(count);
                assertThat(commands).containsExactly("HMGET");
                commands.clear();
                assertThat(catalog.getWorkerDescriptorsAsync(ids).join()).hasSize(count);
                assertThat(commands).containsExactly("HMGET");
            } finally {
                redisClient.removeListener(listener);
            }
        }
    }
}
