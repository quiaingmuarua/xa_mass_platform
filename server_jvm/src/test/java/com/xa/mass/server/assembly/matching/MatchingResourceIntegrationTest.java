package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class MatchingResourceIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> witness;
    private RedisCommands<String, String> redis;

    @BeforeEach void open() {
        scope = RedisTestScope.create("matching_resources");
        client = RedisClient.create(REDIS_URL);
        witness = client.connect(); redis = witness.sync();
    }
    @AfterEach void close() {
        if (redis != null) scope.cleanup(redis);
        if (witness != null) witness.close();
        if (client != null) client.shutdown();
    }

    @Test void phoneOnlyAssemblyMaintainsAndRetainsWithoutPoolOrTaskDemand() {
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone"), null));
        try (var store = new FactsIndexStore(client, scope.keyspace(), MatchingComposition.indexedProperties(groups))) {
            var composition = new MatchingComposition(store, groups, System::currentTimeMillis);
            assertThat(composition.pools()).isEmpty(); assertThat(composition.policies()).isEmpty();
            {
            var catalog = composition.catalog();
                assertThat(catalog.observeRefillDeficits(Map.of("g", List.of()))).isEmpty();
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "first")));
                assertThat(phone(catalog, "first")).containsValue(new WorkerCandidate("w", 0));
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "second")));
                assertThat(phone(catalog, "first")).isEmpty();
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of()));
                assertThat(phone(catalog, "second")).isEmpty();
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "retained")));
                assertThat(phone(catalog, "retained")).containsValue(new WorkerCandidate("w", 0));
                assertThat(composition.budget().available()).isEqualTo(10_000);
            }
        }
        try (var restartedComposition = MatchingComposition.create(client, scope.keyspace(), groups)) {
            var restarted = restartedComposition.catalog();
            assertThat(phone(restarted, "retained")).containsValue(new WorkerCandidate("w", 0));
            assertThat(phone(restarted, "retained")).containsValue(new WorkerCandidate("w", 0));
        }
    }

    @Test void poolConsumptionExpirationAndFullCapacityDoNotChangePropertyIndex() {
        var clock = new AtomicLong(1_000);
        var groups = Map.of("g", new MatchingGroup(Set.of("any"), Set.of("worker.any", "worker.phone"), null));
        try (var store = new FactsIndexStore(client, scope.keyspace(), MatchingComposition.indexedProperties(groups))) {
            var composition = new MatchingComposition(store, groups, clock::get);
            {
            var catalog = composition.catalog();
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w0", Map.of("phone", "number")));
                var target = List.of(new RefillTarget("any", new EligibilityQuery(Map.of()), 1_000));
                for (int batch = 0; batch < 10; batch++) {
                    var held = new LinkedHashMap<String, Long>();
                    for (int i = batch * 100; i < (batch + 1) * 100; i++) held.put("w" + i, (long) (123));
                    assertThat(catalog.refill("g", target, held)).isEqualTo(100);
                }
                assertThat(catalog.refill("g", target, Map.ofEntries(Map.entry("overflow", (long) (123))))).isZero();
                assertThat(phone(catalog, "number")).containsValue(new WorkerCandidate("w0", 0));
                assertThat(catalog.take("g", Map.of("pool", new WorkerQuery("worker.any", Map.of()))))
                        .containsEntry("pool", new WorkerCandidate("w0", 123));
                assertThat(phone(catalog, "number")).containsValue(new WorkerCandidate("w0", 0));
                clock.set(61_001);
                assertThat(catalog.observeRefillDeficits(Map.of())).isEmpty();
                assertThat(composition.budget().available()).isEqualTo(9_001);
                assertThat(catalog.take("g", Map.of("pool", new WorkerQuery("worker.any", Map.of())))).isEmpty();
                assertThat(composition.budget().available()).isEqualTo(10_000);
                assertThat(phone(catalog, "number")).containsValue(new WorkerCandidate("w0", 0));
            }
        }
    }

    @Test void twoFunctionsReadTheSamePhoneResourceWithoutDuplicatingStorageOrConsumingIt() {
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone", "proof.phone"), null));
        try (var store = new FactsIndexStore(client, scope.keyspace(), MatchingComposition.indexedProperties(groups))) {
            var composition = new MatchingComposition(store, Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone"), null)), System::currentTimeMillis);
            var direct = composition.functions().get("worker.phone");
            var functions = new LinkedHashMap<>(composition.functions());
            functions.put("worker.phone", direct);
            functions.put("proof.phone", new QueryFunction() {
                public Object normalizeInput(String g, Object input) {
                    if (!(input instanceof List<?> list) || list.size() != 1) throw new IllegalArgumentException("one phone required");
                    return direct.normalizeInput(g, list.getFirst());
                }
                public Map<String, WorkerCandidate> apply(String g, Map<String, Object> inputs) {
                    return direct.apply(g, inputs);
                }
            });
            {
            var catalog = new DefaultWorkerMatchingCatalog(composition.budget(), composition.pools(),
                    System::currentTimeMillis, composition.policies(), functions, groups, composition.poolOrder(), composition.globalFunctions());
                composition.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "number")));
                var requests = new LinkedHashMap<String, WorkerQuery>();
                requests.put("direct", new WorkerQuery("worker.phone", "number"));
                requests.put("other-input", new WorkerQuery("proof.phone", List.of("number")));
                assertThat(catalog.take("g", requests)).containsOnlyKeys("direct");
                assertThat(catalog.take("g", Map.of("next", requests.get("other-input"))))
                        .containsEntry("next", new WorkerCandidate("w", 0));
                assertThat(redis.hgetall(RedisHashPropertyIndex.key(scope.keyspace(), "g", "phone")))
                        .containsExactlyEntriesOf(Map.of("number", "w"));
                assertThat(composition.pools()).isEmpty();
            }
        }
    }

    @Test void twoFunctionsEnableOnePropertyAndAnUnchangedReportReassertsItsMapping() {
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone", "worker.messaging.phone"), null));
        assertThat(MatchingComposition.indexedProperties(groups).get("g")).containsExactly("phone");
        try (var composition = MatchingComposition.create(client, scope.keyspace(), groups)) {
            var catalog = composition.catalog();
            composition.properties().upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "same")));
            composition.properties().upsertWorkerFactsBatch("g", Map.of("b", Map.of("phone", "same")));
            assertThat(composition.properties().upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "same"))).get("a").status())
                    .isEqualTo(WorkerProperties.MutationStatus.UNCHANGED);
            assertThat(phone(catalog, "same")).containsValue(new WorkerCandidate("a", 0));
        }
    }

    @Test void phonePreflightProtectsFactsAndMembershipWithoutAnyDemand() {
        var groups = Map.of("g", new MatchingGroup(Set.of("messaging", "proof-facts"), Set.of("worker.phone"), null));
        try (var composition = MatchingComposition.create(client, scope.keyspace(), groups)) {
            var catalog = composition.catalog();
            var original = Map.of("phone", "old", "country", "CN", "messaging.enabled", "true", "proofPool", "A", "proofTarget", "yes");
            composition.properties().upsertWorkerFactsBatch("g", Map.of("w", original));
            var factsKey = scope.keyspace().base() + ":matching:worker:facts:g";
            String broken = RedisHashPropertyIndex.key(scope.keyspace(), "g", "phone");
            byte[] dump = redis.dump(broken);
            redis.unlink(broken); redis.set(broken, "wrong-type");
            String before = redis.hget(factsKey, "w");
            assertThatThrownBy(() -> composition.properties().upsertWorkerFactsBatch("g", Map.of("w",
                    Map.of("phone", "new", "country", "US", "messaging.enabled", "true"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(redis.hget(factsKey, "w")).isEqualTo(before);
            assertThat(redis.get(broken)).isEqualTo("wrong-type");
            redis.unlink(broken); redis.restore(broken, 0, dump);
            assertThat(phone(catalog, "old")).containsValue(new WorkerCandidate("w", 0));
            assertThat(phone(catalog, "new")).isEmpty();
        }
    }

    private static Map<String, WorkerCandidate> phone(WorkerMatchingCatalog catalog, String phone) {
        return catalog.take("g", Map.of("m", new WorkerQuery("worker.phone", phone)));
    }
}
