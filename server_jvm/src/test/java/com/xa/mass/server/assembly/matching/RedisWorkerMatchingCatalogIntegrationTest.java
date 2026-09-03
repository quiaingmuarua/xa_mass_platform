package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerMatchingCatalogIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerMatchingCatalog catalog;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("worker_matching_owner");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        catalog = new RedisWorkerMatchingCatalog(redisClient, keyspace);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (catalog != null) {
            catalog.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void workerRefreshReplacesWorkerFactsAndPreservesPlatformFacts() {
        assertThat(catalog.upsertWorkerFacts(
                "worker-1",
                "group-1",
                Map.of("region", "cn", "capacity", 1)
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("battery", 90, "network", "wifi")
        ).status()).isEqualTo(MutationStatus.APPLIED);

        assertThat(catalog.upsertWorkerFacts(
                "worker-1",
                "group-1",
                Map.of("region", "us", "capacity", 2)
        ).status()).isEqualTo(MutationStatus.APPLIED);
        var refreshed = catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1", "missing")
        );

        assertThat(refreshed.get("worker-1").workerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "region", "us",
                        "capacity", 2L
                ));
        assertThat(refreshed.get("worker-1").platformProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "battery", 90L,
                        "network", "wifi"
                ));
        assertThat(refreshed).containsEntry("missing", null);

        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                java.util.Collections.singletonMap("battery", null)
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1")
        ).get("worker-1").platformProperties())
                .containsExactlyEntriesOf(Map.of("network", "wifi"));
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "missing",
                Map.of("battery", 10)
        ).status()).isEqualTo(MutationStatus.NOT_FOUND);
    }

    @Test
    void candidateRulesAreCreateOnlyAndContentIdempotent() {
        Map<String, Object> rule = Map.of(
                "worker.region",
                Map.of("$eq", "cn")
        );
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                Map.of("worker.region", Map.of("$eq", "us"))
        ).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.loadCandidateRules(List.of(
                "task-1",
                "missing"
        )))
                .containsEntry("missing", null);
        assertThat(catalog.loadCandidateRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
    }

    @Test
    void persistentFactsAndRulesSurviveCatalogInstanceReplacement() {
        Map<String, Object> rule = Map.of(
                "worker.region",
                Map.of("$eq", "cn")
        );
        catalog.upsertWorkerFacts(
                "worker-1",
                "group-1",
                Map.of("region", "cn", "capacity", 2)
        );
        catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("network", "wifi")
        );
        catalog.createCandidateRule("task-1", "group-1", rule);

        catalog.close();
        catalog = new RedisWorkerMatchingCatalog(redisClient, keyspace);

        var facts = catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(facts.workerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "region", "cn",
                        "capacity", 2L
                ));
        assertThat(facts.platformProperties())
                .containsExactlyEntriesOf(Map.of("network", "wifi"));
        assertThat(catalog.loadCandidateRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
    }
}
