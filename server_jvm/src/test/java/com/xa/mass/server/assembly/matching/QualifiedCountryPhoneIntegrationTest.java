package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.testsupport.QualifiedCountryFixtures;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.functions.QualifiedCountryPhoneQueryFunction;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class QualifiedCountryPhoneIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private DefaultWorkerMatchingCatalog catalog;
    private MatchingComposition composition;
    private WorkerProperties properties;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final Map<String, MatchingGroup> groups = Map.of(
            "direct", new MatchingGroup(Set.of(), Set.of("worker.messaging.phone")),
            "mixed", new MatchingGroup(Set.of("any"), Set.of("worker.any", "worker.phone", "worker.messaging.phone")));

    @BeforeEach void setup() {
        scope = RedisTestScope.create("messaging_phone");
        client = RedisClient.create(REDIS_URL);
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commands.add(event.getCommand().getType().toString()); }
        });
        connection = client.connect(); redis = connection.sync();
        composition = MatchingComposition.create(client, scope.keyspace(), groups, java.util.List.of(), QualifiedCountryFixtures.DEFINITIONS, java.util.List.of());
        catalog = composition.catalog();
        properties = composition.properties();
    }

    @AfterEach void cleanup() {
        if (catalog != null) composition.close();
        if (redis != null) scope.cleanup(redis);
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    @Test void directOnlyGroupQualifiesOneHundredIdentitiesWithOneLookupAndOneFactsRead() {
        var facts = new LinkedHashMap<String, Map<String, String>>();
        var requests = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < 100; i++) {
            facts.put("w" + i, eligible("CN", "phone" + i));
            requests.put("m" + i, query("phone" + i));
        }
        properties.upsertWorkerFactsBatch("direct", facts);
        commands.clear();
        var found = catalog.take("direct", requests);
        assertThat(found.keySet()).containsExactlyElementsOf(requests.keySet());
        for (int i = 0; i < 100; i++) assertThat(found.get("m" + i)).isEqualTo(new WorkerCandidate("w" + i, 0));
        assertThat(commands).containsExactly("HMGET", "HMGET");
        assertThatThrownBy(found::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(catalog.observeRefillDeficits(Map.of("direct", List.of()))).isEmpty();

        for (int i = 100; i < 1000; i++) requests.put("m" + i, query("extra" + i));
        requests.put("overflow", query("extra")); commands.clear();
        assertThatThrownBy(() -> catalog.take("direct", requests)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        commands.clear();
        assertThat(catalog.take("direct", Map.of("missing", query("absent")))).isEmpty();
        assertThat(commands).containsExactly("HMGET");

        // Restart retains the existing mapping without rebuilding.
        composition.close(); composition = MatchingComposition.create(client, scope.keyspace(), groups, java.util.List.of(), QualifiedCountryFixtures.DEFINITIONS, java.util.List.of());
        catalog = composition.catalog();
        properties = composition.properties();
        assertThat(catalog.take("direct", Map.of("m", query("phone0"))))
                .containsEntry("m", new WorkerCandidate("w0", 0));
    }

    @Test void customDeclarationsShareThePhoneIndexAndUseOnlyTheirOwnWorkerFields() {
        var alpha = new QualifiedCountryDefinition("alpha", "custom.alpha", "custom.alpha.phone", "allow.a", "yes", "nation");
        var beta = new QualifiedCountryDefinition("beta", "custom.beta", "custom.beta.phone", "allow.b", "ok", "region");
        var enabled = Map.of("custom", new MatchingGroup(Set.of("alpha"),
                Set.of(alpha.poolFunctionName(), alpha.phoneFunctionName(), beta.phoneFunctionName())));
        try (var declared = MatchingComposition.create(client, scope.keyspace(), enabled, List.of(), List.of(alpha, beta), java.util.List.of())) {
            declared.properties().upsertWorkerFactsBatch("custom", Map.of(
                    "a", Map.of("phone", "123", "allow.a", "yes", "nation", "CN"),
                    "b", Map.of("phone", "456", "allow.b", "ok", "region", "US")));
            assertThat(MatchingComposition.indexedProperties(enabled, List.of(alpha, beta)).get("custom")).containsExactly("phone");
            assertThat(declared.pools()).containsOnlyKeys("alpha");
            var stock = List.of(RefillTarget.of("alpha", new EligibilityQuery(Map.of()), 100));
            commands.clear();
            assertThat(declared.catalog().refill("custom", stock, Map.of("a", 123L, "b", 456L)).admittedWorkerIds().size()).isEqualTo(1);
            assertThat(commands).containsExactly("HMGET");
            commands.clear();
            assertThat(declared.catalog().take("custom", Map.of("m", new WorkerQuery(alpha.poolFunctionName(), Map.of()))))
                    .containsEntry("m", new WorkerCandidate("a", 123));
            assertThat(commands).isEmpty();
            var queries = new LinkedHashMap<String, WorkerQuery>();
            queries.put("a", new WorkerQuery(alpha.phoneFunctionName(), Map.of("phone", "123")));
            queries.put("b", new WorkerQuery(beta.phoneFunctionName(), Map.of("phone", "456", "country", List.of("US"))));
            assertThat(declared.catalog().take("custom", queries)).containsExactlyInAnyOrderEntriesOf(
                    Map.of("a", new WorkerCandidate("a", 0), "b", new WorkerCandidate("b", 0)));
            assertThat(commands).containsExactly("HMGET", "HMGET", "HMGET", "HMGET");
            commands.clear();
            assertThat(declared.catalog().take("custom", Map.of("wrong", new WorkerQuery(beta.phoneFunctionName(), Map.of("phone", "123"))))).isEmpty();
            assertThat(commands).containsExactly("HMGET", "HMGET");
        }
    }

    @Test void countryIntersectionFiltersFactsAndGenericPhoneKeepsItsIndependentMeaning() {
        properties.upsertWorkerFactsBatch("mixed", Map.of("cn", eligible("CN", "same"), "us", eligible("US", "same"),
                "disabled", Map.of("country", "CN", "phone", "disabled", "messaging.enabled", "false"),
                "invalid", eligible("invalid", "invalid"), "missing", eligible("CN", "missing")));
        // Make the last mapping deterministic; earlier US facts remain but are not a fallback.
        properties.upsertWorkerFactsBatch("mixed", Map.of("cn", eligible("CN", "same")));
        redis.hdel(factsKey("mixed"), "missing");
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("us", new WorkerQuery("worker.messaging.phone", Map.of("phone", "same", "country", List.of("US"))));
        requests.put("disabled", query("disabled"));
        requests.put("cn", new WorkerQuery("worker.messaging.phone", Map.of("phone", "same", "country", List.of("CN"))));
        requests.put("invalid", query("invalid")); requests.put("missing", query("missing"));
        commands.clear();
        var found = catalog.take("mixed", requests);
        assertThat(found.keySet()).containsExactly("cn");
        assertThat(found.values()).containsExactly(new WorkerCandidate("cn", 0));
        assertThat(commands).containsExactly("HMGET", "HMGET");
        assertThat(catalog.take("mixed", Map.of("generic", new WorkerQuery("worker.phone", "disabled"))))
                .containsEntry("generic", new WorkerCandidate("disabled", 0));
    }

    @Test void phoneChangedAfterLookupIsRejectedByCurrentFactsWithoutRefetching() {
        properties.upsertWorkerFactsBatch("direct", Map.of("w", eligible("CN", "old")));
        try (var storage = new FactsIndexStore(client, scope.keyspace(), MatchingComposition.indexedProperties(groups, QualifiedCountryFixtures.DEFINITIONS))) {
            var function = new QualifiedCountryPhoneQueryFunction(new RedisHashPropertyIndex(storage::commands, scope.keyspace(), "phone"), (group, ids) -> {
                assertThat(ids).containsExactly("w");
                properties.upsertWorkerFactsBatch(group, Map.of("w", eligible("CN", "new")));
                return storage.readWorkerFacts(group, ids);
            }, QualifiedCountryFixtures.eligibility());
            assertThat(function.apply("direct", Map.of("m", function.normalizeInput("direct", Map.of("phone", "old"))))).isEmpty();
        }
        assertThat(catalog.take("direct", Map.of("m", query("new")))).containsEntry("m", new WorkerCandidate("w", 0));
    }

    @Test void fullAdmissionPrecedesEffectsButFactsFailureKeepsEarlierPoolConsumption() {
        properties.upsertWorkerFactsBatch("mixed", Map.of("bad", eligible("CN", "bad")));
        redis.hset(factsKey("mixed"), "bad", "not-json");
        assertThat(catalog.refill("mixed", List.of(new RefillTarget("any", new EligibilityQuery(Map.of()), 1)),
                Map.of("pooled", 123L)).admittedWorkerIds().size()).isEqualTo(1);
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("pool", new WorkerQuery("worker.any", Map.of())); requests.put("bad", query("bad"));
        requests.put("invalid", new WorkerQuery("worker.messaging.phone", Map.of()));
        commands.clear();
        assertThatThrownBy(() -> catalog.take("mixed", requests)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        assertThat(catalog.observeRefillDeficits(Map.of("mixed",
                List.of(new RefillTarget("any", new EligibilityQuery(Map.of()), 1))))).isEmpty();
        requests.remove("invalid");
        assertThatThrownBy(() -> catalog.take("mixed", requests)).isInstanceOf(RuntimeException.class);
        assertThat(commands).containsExactly("HMGET", "HMGET");
        assertThat(catalog.take("mixed", Map.of("next", new WorkerQuery("worker.any", Map.of())))).isEmpty();
    }

    private String factsKey(String group) { return scope.keyspace().base() + ":matching:worker:facts:" + group; }
    private static WorkerQuery query(String phone) { return new WorkerQuery("worker.messaging.phone", Map.of("phone", phone)); }
    private static Map<String, String> eligible(String country, String phone) {
        return Map.of("country", country, "phone", phone, "messaging.enabled", "true");
    }
}
