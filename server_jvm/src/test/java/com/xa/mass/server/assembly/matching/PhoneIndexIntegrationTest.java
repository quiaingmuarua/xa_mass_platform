package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.index.RedisHashPropertyIndex;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.DefaultWorkerMatchingCatalog;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.WorkerProperties;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class PhoneIndexIntegrationTest {
    private RedisTestScope scope;
    private RedisKeyspace keyspace;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private DefaultWorkerMatchingCatalog catalog;
    private MatchingComposition composition;
    private WorkerProperties properties;
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        scope = RedisTestScope.create("phone_index"); keyspace = scope.keyspace();
        client = RedisClient.create(REDIS_URL);
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commands.add(event.getCommand().getType().toString()); }
        });
        connection = client.connect(); redis = connection.sync(); composition = create(); catalog = composition.catalog(); properties = composition.properties();
    }
    private MatchingComposition create() {
        return com.xa.mass.workermatching.MatchingComposition.create(client, keyspace, Map.of(
                "g", new com.xa.mass.workermatching.MatchingGroup(Set.of("any","messaging"), Set.of("worker.any","worker.phone","worker.messaging.available")),
                "other", new com.xa.mass.workermatching.MatchingGroup(Set.of(), Set.of("worker.phone"))));
    }
    @AfterEach void cleanup() {
        if (catalog != null) composition.close();
        if (redis != null) scope.cleanup(redis);
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
    private Map<String, WorkerCandidate> find(String group, String phone, int count) {
        var requests = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < count; i++) requests.put("m" + i, new WorkerQuery("worker.phone", phone));
        return catalog.take(group, requests);
    }
    private String root(String group) { return RedisHashPropertyIndex.key(keyspace, group, "phone"); }
    private String factsKey() { return keyspace.base() + ":matching:worker:facts:g"; }
    private void write(String id, String phone) { properties.upsertWorkerFactsBatch("g", Map.of(id, Map.of("phone", phone))); }

    @Test void phoneMembershipIsIndependentOfMessagingAndUsesOneHmget() {
        var batch = new LinkedHashMap<String, Map<String, String>>();
        batch.put("a", Map.of("phone", "+1"));
        batch.put("b", Map.of("phone", "+1", "messaging.enabled", "false"));
        batch.put("c", Map.of("phone", "+2", "country", "invalid"));
        commands.clear(); properties.upsertWorkerFactsBatch("g", batch);
        assertThat(commands).containsExactly("EVAL");
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("first", new WorkerQuery("worker.phone", "+1"));
        requests.put("other", new WorkerQuery("worker.phone", "+2"));
        requests.put("duplicate", new WorkerQuery("worker.phone", "+1"));
        requests.put("missing", new WorkerQuery("worker.phone", "missing"));
        commands.clear(); var found = catalog.take("g", requests);
        assertThat(commands).containsExactly("HMGET");
        assertThat(found.keySet()).containsExactly("first", "other");
        assertThat(found.values()).containsExactly(new WorkerCandidate("b", 0), new WorkerCandidate("c", 0));
        assertThat(redis.hgetall(root("g"))).containsExactlyInAnyOrderEntriesOf(Map.of("+1", "b", "+2", "c"));
        assertThat(find("g", "+1", 100).values()).containsExactly(new WorkerCandidate("b", 0));
        assertThat(find("other", "+1", 1)).isEmpty();
        assertThat(catalog.take("g", Map.of("pool", new WorkerQuery("worker.messaging.available", Map.of())))).isEmpty();
        assertThatThrownBy(found::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void exactReplacementAndRemovalDoNotDeleteAnotherWorkersMappingOrFindAFallback() {
        write("a", " +1 ");
        assertThat(find("g", "+1", 1)).isEmpty();
        assertThat(find("g", " +1 ", 1)).hasSize(1);
        write("b", " +1 ");
        write("a", "+2");
        assertThat(find("g", " +1 ", 1).values()).containsExactly(new WorkerCandidate("b", 0));
        write("a", " +1 "); // a becomes the last writer; b remains in Facts.
        assertThat(find("g", "+2", 1)).isEmpty();
        write("a", "");
        assertThat(find("g", " +1 ", 1)).isEmpty();
        assertThat(redis.hget(factsKey(), "b")).contains(" +1 ");
        assertThat(properties.upsertWorkerFactsBatch("g", Map.of("b", Map.of("phone", " +1 "))).get("b").status())
                .isEqualTo(com.xa.mass.workermatching.WorkerProperties.MutationStatus.UNCHANGED);
        assertThat(find("g", " +1 ", 1).values()).containsExactly(new WorkerCandidate("b", 0));
        properties.upsertWorkerFactsBatch("g", Map.of("b", Map.of()));
        assertThat(redis.hgetall(root("g"))).isEmpty();
    }

    @Test void platformPatchesNeverReclaimPhoneButIdenticalWorkerReportsDo() {
        write("a", "shared"); write("b", "shared");
        commands.clear();
        properties.patchWorkerPlatformProperties("g", "a", Map.of("phone", "platform", "nested", Map.of("list", List.of())));
        assertThat(commands).containsExactly("EVAL");
        assertThat(find("g", "shared", 1).values()).containsExactly(new WorkerCandidate("b", 0));
        assertThat(find("g", "platform", 1)).isEmpty();
        assertThat(properties.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "shared"))).get("a").status())
                .isEqualTo(com.xa.mass.workermatching.WorkerProperties.MutationStatus.UNCHANGED);
        assertThat(find("g", "shared", 1).values()).containsExactly(new WorkerCandidate("a", 0));
        redis.unlink(root("g")); redis.set(root("g"), "wrong-type");
        assertThat(properties.patchWorkerPlatformProperties("g", "a", Map.of("independent", true)).status())
                .isEqualTo(com.xa.mass.workermatching.WorkerProperties.MutationStatus.APPLIED);
        assertThat(redis.get(root("g"))).isEqualTo("wrong-type");
    }

    @Test void batchCrossChangesCheckOwnershipAtWriteTimeAndLaterRowsWin() {
        write("a", "left"); write("b", "right");
        var batch = new LinkedHashMap<String, Map<String, String>>();
        batch.put("a", Map.of("phone", "right")); batch.put("b", Map.of("phone", "left"));
        properties.upsertWorkerFactsBatch("g", batch);
        assertThat(redis.hgetall(root("g"))).containsExactlyInAnyOrderEntriesOf(Map.of("right", "a", "left", "b"));
        batch.put("a", Map.of("phone", "left")); batch.put("b", Map.of("phone", "third"));
        properties.upsertWorkerFactsBatch("g", batch);
        assertThat(redis.hgetall(root("g"))).containsExactlyInAnyOrderEntriesOf(Map.of("left", "a", "third", "b"));
        batch.put("a", Map.of("phone", "shared")); batch.put("b", Map.of("phone", "shared"));
        properties.upsertWorkerFactsBatch("g", batch);
        assertThat(redis.hgetall(root("g"))).containsExactlyEntriesOf(Map.of("shared", "b"));
        properties.upsertWorkerFactsBatch("g", Map.of("a", Map.of()));
        assertThat(redis.hget(root("g"), "shared")).isEqualTo("b");
    }

    @Test void lateCorruptFactsCannotPartiallyCommitEarlierRows() {
        write("a", "old-a"); write("b", "old-b");
        var batch = new LinkedHashMap<String, Map<String, String>>();
        batch.put("a", Map.of("phone", "new-a")); batch.put("b", Map.of("phone", "new-b"));
        redis.hset(factsKey(), "b", "[]");
        assertThatThrownBy(() -> properties.upsertWorkerFactsBatch("g", batch)).isInstanceOf(RuntimeException.class);
        assertThat(redis.hget(factsKey(), "a")).contains("old-a");
        assertThat(redis.hgetall(root("g"))).containsExactlyInAnyOrderEntriesOf(Map.of("old-a", "a", "old-b", "b"));
        redis.hset(factsKey(), "b", "{\"phone\":\"old-b\"}");
        redis.hset(keyspace.base() + ":matching:worker:platform-properties:g", "b", "[]");
        assertThatThrownBy(() -> properties.upsertWorkerFactsBatch("g", batch)).isInstanceOf(RuntimeException.class);
        assertThat(redis.hget(factsKey(), "a")).contains("old-a");
        assertThat(redis.hget(root("g"), "new-a")).isNull();
    }

    @Test void propertyAndGroupIsolationAndAllHashPreflightShareOneFactsCommit() {
        var configured = new LinkedHashSet<>(List.of("phone", "account:id"));
        String account = RedisHashPropertyIndex.key(keyspace, "g", "account:id");
        try (var store = new FactsIndexStore(client, keyspace, Map.of("g", configured, "g:*[x]", configured))) {
            commands.clear();
            store.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "same", "account:id", "same")));
            assertThat(commands).containsExactly("EVAL");
            store.upsertWorkerFactsBatch("g:*[x]", Map.of("b", Map.of("phone", "same")));
            assertThat(new RedisHashPropertyIndex(store::commands, keyspace, "account:id").lookup("g", List.of("same")))
                    .containsExactlyEntriesOf(Map.of("same", "a"));
            assertThat(new RedisHashPropertyIndex(store::commands, keyspace, "phone").lookup("g:*[x]", List.of("same")))
                    .containsExactlyEntriesOf(Map.of("same", "b"));
            redis.unlink(account); redis.set(account, "wrong-type");
            assertThatThrownBy(() -> store.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "new"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(redis.hget(root("g"), "same")).isEqualTo("a");
            assertThat(redis.hget(factsKey(), "a")).contains("same");
        }
    }

    @Test void restartPreservesWinnerAndIgnoresRetiredIndexesWithoutBackfill() {
        write("a", "same"); write("b", "same");
        String oldRoot = keyspace.base() + ":matching:worker:index:Zw:phone";
        redis.set(oldRoot, "retired-corrupt-root"); redis.set(oldRoot + ":value:legacy", "retired-corrupt-set");
        composition.close(); commands.clear(); composition = create(); catalog = composition.catalog(); properties = composition.properties();
        assertThat(commands).isEmpty();
        assertThat(find("g", "same", 1).values()).containsExactly(new WorkerCandidate("b", 0));
        assertThat(redis.get(oldRoot)).isEqualTo("retired-corrupt-root");
        assertThat(redis.get(oldRoot + ":value:legacy")).isEqualTo("retired-corrupt-set");
        redis.unlink(root("g")); composition.close(); commands.clear(); composition = create(); catalog = composition.catalog(); properties = composition.properties();
        assertThat(commands).isEmpty();
        assertThat(find("g", "same", 1)).isEmpty();
        write("a", "same");
        assertThat(find("g", "same", 1).values()).containsExactly(new WorkerCandidate("a", 0));
    }

    @Test void invalidLateInputDoesNotExecuteEarlierLookupAndBatchRemainsBounded() {
        var input = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < 100; i++) input.put("m" + i, new WorkerQuery("worker.phone", "+" + i));
        commands.clear(); assertThat(catalog.take("g", input)).isEmpty(); assertThat(commands).containsExactly("HMGET");
        input.put("m101", new WorkerQuery("workerId", "w")); commands.clear();
        assertThatThrownBy(() -> catalog.take("g", input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty(); input.remove("m101"); input.put("m99", new WorkerQuery("workerId", " "));
        assertThatThrownBy(() -> catalog.take("g", input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
    }

    @Test void concurrentReplacementsLeaveOnlyTheWinningValueAndPreservePlatformFacts() throws Exception {
        write("w", "initial"); properties.patchWorkerPlatformProperties("g", "w", Map.of("retained", true));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var other = create(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return properties.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "left"))); });
            var b = executor.submit(() -> { start.await(); return other.properties().upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "right"))); });
            start.countDown(); a.get(5, java.util.concurrent.TimeUnit.SECONDS); b.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        String winner = (String) FactsIndexStore.decodeObject(redis.hget(factsKey(), "w")).get("phone");
        assertThat(winner).isIn("left", "right");
        assertThat(redis.hgetall(root("g"))).containsExactlyEntriesOf(Map.of(winner, "w"));
        assertThat(redis.hget(keyspace.base() + ":matching:worker:platform-properties:g", "w")).contains("retained");
    }

    @Test void concurrentWorkersKeepTheirFactsButOnlyTheLastMappingAndLoserCannotDeleteIt() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var other = create(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { start.await(); return properties.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "same"))); });
            var b = executor.submit(() -> { start.await(); return other.properties().upsertWorkerFactsBatch("g", Map.of("b", Map.of("phone", "same"))); });
            start.countDown(); a.get(5, java.util.concurrent.TimeUnit.SECONDS); b.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        String winner = redis.hget(root("g"), "same");
        assertThat(winner).isIn("a", "b");
        assertThat(redis.hget(factsKey(), "a")).contains("same");
        assertThat(redis.hget(factsKey(), "b")).contains("same");
        String loser = winner.equals("a") ? "b" : "a";
        write(loser, "other");
        assertThat(redis.hget(root("g"), "same")).isEqualTo(winner);
        assertThat(redis.hget(root("g"), "other")).isEqualTo(loser);
    }

    @Test void mixedPoolPhoneAndIdentityKeepFirstAssociationWithoutConsumingTheIndex() {
        properties.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "+1")));
        assertThat(catalog.refill("g", List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)), Map.ofEntries(Map.entry("a", (long) (123))))).isEqualTo(1);
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("pool", new WorkerQuery("worker.any", Map.of()));
        requests.put("phone", new WorkerQuery("worker.phone", "+1"));
        requests.put("identity", new WorkerQuery("workerId", "b"));
        requests.put("late-invalid", new WorkerQuery("worker.phone", 42));
        commands.clear();
        assertThatThrownBy(() -> catalog.take("g", requests)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        requests.remove("late-invalid"); var found = catalog.take("g", requests);
        assertThat(found.keySet()).containsExactly("pool", "identity");
        assertThat(found.get("pool")).isEqualTo(new WorkerCandidate("a", 123));
        assertThat(found.get("identity")).isEqualTo(new WorkerCandidate("b", 0));
        assertThat(commands).containsExactly("HMGET");
        assertThat(find("g", "+1", 1).values()).containsExactly(new WorkerCandidate("a", 0));
    }

    @Test void phoneReadFailureDoesNotRollBackAnEarlierPoolConsumption() throws Exception {
        assertThat(catalog.refill("g", List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)), Map.ofEntries(Map.entry("a", (long) (123))))).isEqualTo(1);
        redis.unlink(root("g")); redis.set(root("g"), "wrong-type");
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("pool", new WorkerQuery("worker.any", Map.of()));
        requests.put("phone", new WorkerQuery("worker.phone", "corrupt"));
        requests.put("identity", new WorkerQuery("workerId", "a"));
        assertThatThrownBy(() -> catalog.take("g", requests)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.take("g", Map.of("next", new WorkerQuery("worker.any", Map.of())))).isEmpty();
        // A separate invocation is still independent; no identity hint or Pool replay was retained.
        assertThat(catalog.take("g", Map.of("identity", new WorkerQuery("workerId", "a"))))
                .containsEntry("identity", new WorkerCandidate("a", 0));
    }
}
