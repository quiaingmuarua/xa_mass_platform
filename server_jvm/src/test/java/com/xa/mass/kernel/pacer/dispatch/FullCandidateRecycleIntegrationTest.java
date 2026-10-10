package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.xa.mass.kernel.score.redis.WorkerScoreRedisFixture.*;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

@Tag("redis-owner")
class FullCandidateRecycleIntegrationTest {
    @Test void elevenGroupsShareOneThousandRealRecyclesAndKeepOrdinaryCapacity() {
        var scope = RedisTestScope.create("full_candidate_budget");
        var client = RedisClient.create(REDIS_URL);
        var commands = new ArrayList<String>();
        client.addListener(new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        var clock = new AtomicLong(System.currentTimeMillis()); var nanos = new AtomicLong();
        var groups = new LinkedHashMap<String, MatchingGroup>();
        for (int i = 0; i < 11; i++) groups.put("g" + i, new MatchingGroup(Set.of("any"), Set.of("worker.any")));
        try (var connection = client.connect(); var scores = new RedisWorkerScoreCore(client, scope.keyspace(), clock::get);
                var matching = new MatchingComposition(new FactsIndexStore(client, scope.keyspace(), Map.of()), groups,
                        clock::get, List.of(), List.of(), List.of())) {
            var redis = connection.sync();
            try {
                var hints = new CandidateRecycleHints(nanos::get);
                var identities = new ArrayList<String>();
                for (int i = 0; i < 100; i++) { identities.add("hint" + i); identities.add("old" + i); }
                for (String group : groups.keySet()) {
                    var ordinary = new LinkedHashMap<String, Long>();
                    for (String id : identities) {
                        long score = dueOrdinaryScore(clock.get() - (id.startsWith("old") ? 35_000 : 0));
                        ordinary.put(id, score); redis.zadd(scope.keyspace().base() + ":worker:score:" + group, score, id);
                    }
                    var held = new LinkedHashMap<String, Long>();
                    scores.candidateizeObservedHotScores(group, ordinary).forEach((id, result) -> held.put(id, result.score()));
                    hints.offer(group, held, identities.stream().filter(id -> id.startsWith("hint")).toList());
                }
                clock.addAndGet(10_000); nanos.set(TimeUnit.SECONDS.toNanos(10));
                var policy = new WorkerEligibilityRefillPolicy(scores, matching.catalog(), null, 100, clock::get);
                var tasks = groups.keySet().stream().map(group -> new TaskDescriptor("task-" + group, "project", group,
                        TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "1"), List.of(), null, Map.of())).toList();
                commands.clear(); policy.refill(List.copyOf(groups.keySet()), tasks, hints);
                assertThat(Collections.frequency(commands, "ZRANGEBYSCORE")).isEqualTo(10);
                assertThat(Collections.frequency(commands, "EVAL")).isEqualTo(10);
                assertThat(commands).doesNotContain("ZMSCORE", "HMGET", "TIME");
                for (String group : groups.keySet()) {
                    var current = readScores(redis, scope.keyspace(), group, identities);
                    long expected = group.equals("g10") ? 0 : 50;
                    for (String prefix : List.of("hint", "old"))
                        assertThat(current.entrySet().stream().filter(row -> row.getKey().startsWith(prefix)
                                && mark(row.getValue()) == 0).count()).isEqualTo(expected);
                }
                assertThat(hints.pending()).isEqualTo(600);
                policy.refill(List.copyOf(groups.keySet()), tasks, hints);
                assertThat(readScores(redis, scope.keyspace(), "g10", identities).values().stream().filter(score -> mark(score) == 0).count()).isEqualTo(100);
            } finally { scope.cleanup(redis); }
        } finally { client.shutdown(); }
    }
    @ParameterizedTest @ValueSource(strings = {"free", "executing", "new-generation"})
    void fullCandidateReturnsEarlyButCannotOverwriteAnotherExecutionOrGeneration(String state) {
        var scope = RedisTestScope.create("full_candidate_recycle");
        var client = RedisClient.create(REDIS_URL);
        var clock = new AtomicLong(System.currentTimeMillis());
        var nanos = new AtomicLong();
        var declaration = new PartitionedLeasePoolDefinition("leases", "lease.available", "phone", "country", Set.of("A"));
        try (var connection = client.connect(); var scores = new RedisWorkerScoreCore(client, scope.keyspace(), clock::get);
                var matching = new MatchingComposition(new FactsIndexStore(client, scope.keyspace(), Map.of()),
                        Map.of("g", new MatchingGroup(Set.of("leases"), Set.of("lease.available"))), clock::get,
                        List.of(), List.of(), List.of(declaration))) {
            var redis = connection.sync();
            try {
                var targets = List.of(target("CN"), target("US"));
                var tasks = List.of(new TaskDescriptor("task", "project", "g", TaskIdleDisposition.PARK_WHEN_IDLE,
                        Map.of("priority", "0", "maxRetryTimes", "1"), targets, null, Map.of()));
                var facts = Map.of("resident", Map.of("phone", "r", "country", "CN"),
                        "a-full", Map.of("phone", "a", "country", "CN"), "z-supply", Map.of("phone", "z", "country", "US"));
                matching.properties().upsertWorkerFactsBatch("g", facts);
                long ordinary = dueOrdinaryScore(clock.get());
                String key = scope.keyspace().base() + ":worker:score:g";
                facts.keySet().forEach(id -> redis.zadd(key, ordinary, id));
                long residentFence = scores.candidateizeObservedHotScores("g", Map.of("resident", ordinary)).get("resident").score();
                assertThat(matching.catalog().refill("g", targets, Map.of("resident", residentFence)).admittedWorkerIds()).containsExactly("resident");
                var policy = new WorkerEligibilityRefillPolicy(scores, matching.catalog(), null, 100, clock::get);
                var hints = new CandidateRecycleHints(nanos::get);
                assertThat(policy.refill(List.of("g"), tasks, hints)).isZero();
                assertThat(hints.pending()).isEqualTo(1);
                long fullFence = readScores(redis, scope.keyspace(), "g", List.of("a-full")).get("a-full");
                Long protectedFence = null;
                if (state.equals("executing")) {
                    var result = scores.acquireObservedHotScoreLeases("g", Map.of("a-full", fullFence), clock.get() + 30_000).get("a-full");
                    assertThat(result.status()).isEqualTo(TRANSITIONED); protectedFence = result.score();
                } else if (state.equals("new-generation")) {
                    protectedFence = scores.recycleObservedHotCandidates("g", Map.of("a-full", fullFence)).get("a-full").score();
                    clock.addAndGet(100);
                    protectedFence = scores.candidateizeObservedHotScores("g", Map.of("a-full", protectedFence)).get("a-full").score();
                }
                var cn = new WorkerQuery("lease.available", Map.of("partition", "A", "country", "CN"));
                assertThat(matching.catalog().take("g", Map.of("item", cn)).get("item").workerId()).isEqualTo("resident");
                clock.addAndGet(9000); nanos.set(TimeUnit.SECONDS.toNanos(9));
                policy.refill(List.of("g"), tasks, hints);
                if (state.equals("free")) assertThat(readScores(redis, scope.keyspace(), "g", List.of("a-full"))).containsEntry("a-full", fullFence);
                clock.addAndGet(1000); nanos.set(TimeUnit.SECONDS.toNanos(10));
                policy.refill(List.of("g"), tasks, hints);
                assertThat(hints.pending()).isZero();
                if (protectedFence != null) {
                    assertThat(readScores(redis, scope.keyspace(), "g", List.of("a-full"))).containsEntry("a-full", protectedFence);
                    assertThat(matching.catalog().take("g", Map.of("item", cn))).isEmpty();
                } else {
                    clock.addAndGet(100);
                    assertThat(policy.refill(List.of("g"), tasks, hints)).isEqualTo(1);
                    var fresh = matching.catalog().take("g", Map.of("item", cn)).get("item");
                    assertThat(fresh.workerId()).isEqualTo("a-full"); assertThat(fresh.expectedScore()).isNotEqualTo(fullFence);
                }
            } finally { scope.cleanup(redis); }
        } finally { client.shutdown(); }
    }
    private static RefillTarget target(String country) {
        return new RefillTarget("leases", new EligibilityQuery(Map.of("partition", List.of("A"), "worker.country", List.of(country))), 1);
    }
}
