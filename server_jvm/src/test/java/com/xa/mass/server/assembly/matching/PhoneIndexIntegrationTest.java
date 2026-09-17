package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private RedisWorkerMatchingCatalog catalog;
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        scope = RedisTestScope.create("phone_index"); keyspace = scope.keyspace();
        client = RedisClient.create(REDIS_URL);
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commands.add(event.getCommand().getType().toString()); }
        });
        connection = client.connect(); redis = connection.sync(); catalog = create();
    }
    private RedisWorkerMatchingCatalog create() {
        return com.xa.mass.workermatching.MatchingComposition.create(client, keyspace, Map.of(
                "g", new com.xa.mass.workermatching.MatchingGroup(Set.of("any","messaging"), Set.of("worker.any","worker.phone","worker.messaging.available")),
                "other", new com.xa.mass.workermatching.MatchingGroup(Set.of(), Set.of("worker.phone"))));
    }
    @AfterEach void cleanup() {
        if (catalog != null) catalog.close();
        if (redis != null) scope.cleanup(redis);
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
    private Map<String, WorkerCandidate> find(String group, String phone, int count) {
        var requests = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < count; i++) requests.put("m" + i, new WorkerQuery("worker.phone", phone));
        return catalog.take(group, requests);
    }
    private String root(String group) {
        return keyspace.base() + ":matching:worker:index:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(group.getBytes(StandardCharsets.UTF_8)) + ":phone";
    }
    private String value(String group, String phone) throws Exception {
        return root(group) + ":value:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(phone.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void phoneMembershipIsIndependentOfMessagingAndUsesOneReadOnlyBatch() throws Exception {
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "+1"), "b", Map.of("phone", "+1", "messaging.enabled", "false"),
                "c", Map.of("phone", "+2", "country", "invalid")));
        var requests = new LinkedHashMap<String, WorkerQuery>();
        requests.put("a", new WorkerQuery("worker.phone", "+1")); requests.put("b", new WorkerQuery("worker.phone", "+2"));
        requests.put("c", new WorkerQuery("worker.phone", "+1")); requests.put("missing", new WorkerQuery("worker.phone", "missing"));
        commands.clear(); var found = catalog.take("g", requests);
        assertThat(commands).containsExactly("EVAL");
        assertThat(found.keySet()).containsExactly("a", "b", "c");
        assertThat(found.get("b").workerId()).isEqualTo("c");
        assertThat(found.values()).extracting(WorkerCandidate::workerId).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(found.values()).allMatch(candidate -> candidate.expectedScore() == 0);
        assertThat(redis.scard(value("g", "+1"))).isEqualTo(2);
        assertThat(find("g", "+1", 100)).hasSize(2);
        assertThat(find("other", "+1", 1)).isEmpty();
        assertThat(catalog.take("g", Map.of("pool", new WorkerQuery("worker.messaging.available", Map.of())))).isEmpty();
        assertThatThrownBy(found::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void replacementRemovalAndPlatformPatchMaintainExactPropertySemantics() throws Exception {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", " +1 ")));
        assertThat(find("g", "+1", 1)).isEmpty(); assertThat(find("g", " +1 ", 1)).hasSize(1);
        catalog.patchWorkerPlatformProperties("g", "w", Map.of("phone", "platform"));
        assertThat(find("g", "platform", 1)).isEmpty(); assertThat(find("g", " +1 ", 1)).hasSize(1);
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "+2")));
        assertThat(find("g", " +1 ", 1)).isEmpty(); assertThat(redis.exists(value("g", " +1 "))).isZero();
        assertThat(find("g", "+2", 1)).hasSize(1);
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "")));
        assertThat(find("g", "+2", 1)).isEmpty(); assertThat(redis.hget(root("g"), "w")).isNull();
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "+3")));
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of()));
        assertThat(find("g", "+3", 1)).isEmpty();
    }

    @Test void allIndexPreflightPrecedesAnyFactsOrMembershipWrite() throws Exception {
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "old"), "b", Map.of("phone", "old")));
        redis.set(value("g", "bad"), "corrupt-type");
        var batch = new LinkedHashMap<String, Map<String, String>>();
        batch.put("a", Map.of("phone", "good")); batch.put("b", Map.of("phone", "bad"));
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", batch)).isInstanceOf(RuntimeException.class);
        assertThat(redis.hget(root("g"), "a")).isEqualTo("old");
        assertThat(redis.hget(keyspace.base() + ":matching:worker:facts:g", "a")).contains("old");
        assertThat(find("g", "good", 1)).isEmpty(); assertThat(find("g", "old", 2)).hasSize(2);
        assertThatThrownBy(() -> find("g", "bad", 1)).isInstanceOf(RuntimeException.class);
    }

    @Test void rebuildUsesExistingFactsAndRetainsGroupIsolation() throws Exception {
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "+1")));
        catalog.upsertWorkerFactsBatch("other", Map.of("b", Map.of("phone", "+2")));
        redis.sadd(value("g", "+1"), "stale"); redis.hset(root("g"), "a", "wrong");
        assertThat(find("g", "+1", 10)).isEmpty();
        catalog.close(); catalog = create();
        assertThat(find("g", "+1", 10).values()).extracting(WorkerCandidate::workerId).containsExactly("a");
        assertThat(find("other", "+2", 10).values()).extracting(WorkerCandidate::workerId).containsExactly("b");
        assertThat(redis.smembers(value("g", "+1"))).containsExactly("a");
    }

    @Test void invalidLateInputDoesNotExecuteEarlierLookupAndBatchRemainsBounded() {
        var input = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < 100; i++) input.put("m" + i, new WorkerQuery("worker.phone", "+" + i));
        commands.clear(); assertThat(catalog.take("g", input)).isEmpty(); assertThat(commands).containsExactly("EVAL");
        input.put("m101", new WorkerQuery("workerId", "w")); commands.clear();
        assertThatThrownBy(() -> catalog.take("g", input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty(); input.remove("m101"); input.put("m99", new WorkerQuery("workerId", " "));
        assertThatThrownBy(() -> catalog.take("g", input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
    }

    @Test void mixedPoolPhoneAndIdentityKeepFirstAssociationWithoutConsumingTheIndex() {
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("phone", "+1")));
        assertThat(catalog.refill("g", List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)), List.of(new com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate("a", 123, System.currentTimeMillis() + 60_000)))).isEqualTo(1);
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
        assertThat(commands).containsExactly("EVAL");
        assertThat(find("g", "+1", 1).values()).containsExactly(new WorkerCandidate("a", 0));
    }

    @Test void concurrentReplacementsLeaveOnlyTheWinningPhoneAndKeepPlatformFacts() throws Exception {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "initial")));
        catalog.patchWorkerPlatformProperties("g", "w", Map.of("retained", true));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var other = create(); var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "left"))); });
            var b = executor.submit(() -> { start.await(); return other.upsertWorkerFactsBatch("g", Map.of("w", Map.of("phone", "right"))); });
            start.countDown(); a.get(5, java.util.concurrent.TimeUnit.SECONDS); b.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        String winner = redis.hget(root("g"), "w");
        assertThat(winner).isIn("left", "right");
        assertThat(find("g", winner, 1).values()).containsExactly(new WorkerCandidate("w", 0));
        assertThat(find("g", winner.equals("left") ? "right" : "left", 1)).isEmpty();
        assertThat(find("g", "initial", 1)).isEmpty();
        assertThat(redis.hget(keyspace.base() + ":matching:worker:facts:g", "w")).contains(winner);
        assertThat(redis.hget(keyspace.base() + ":matching:worker:platform-properties:g", "w")).contains("retained");
    }

    @Test void phoneReadFailureDoesNotRollBackAnEarlierPoolConsumption() throws Exception {
        assertThat(catalog.refill("g", List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)), List.of(new com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate("a", 123, System.currentTimeMillis() + 60_000)))).isEqualTo(1);
        redis.set(value("g", "corrupt"), "wrong-type");
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
