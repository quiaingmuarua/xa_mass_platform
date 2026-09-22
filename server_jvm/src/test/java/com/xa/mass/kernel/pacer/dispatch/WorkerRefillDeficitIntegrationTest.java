package com.xa.mass.kernel.pacer.dispatch;

import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.STALE;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.INVALID;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.NOOP;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity.*;
import static com.xa.mass.kernel.score.redis.WorkerScoreRedisFixture.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.MatchingGroup;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual Pacer policy, Matching stock and Redis fences; no duplicate refill algorithm. */
@Tag("redis-owner")
class WorkerRefillDeficitIntegrationTest {
    @Test
    void laterPoolDemandDoesNotCopyStockAndDirectAcquisitionNeedsNoPoolNotification() {
        var scope = RedisTestScope.create("refill_later_pool");
        var client = RedisClient.create(REDIS_URL);
        var commands = new CopyOnWriteArrayList<String>();
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        String group = "g";
        var country = new RefillTarget("country", new EligibilityQuery(Map.of()), 100);
        var messaging = new RefillTarget("messaging", new EligibilityQuery(Map.of("worker.country", List.of("US"))), 1);
        var countryTask = new TaskDescriptor("country-task", "test-project", group, TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), List.of(country), null, Map.of());
        var messagingTask = new TaskDescriptor("messaging-task", "test-project", group, TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), List.of(messaging), null, Map.of());
        try (var connection = client.connect();
                var scores = new RedisWorkerScoreCore(client, scope.keyspace());
                var matchingComposition = MatchingComposition.create(client, scope.keyspace(), Map.of(group,
                        new MatchingGroup(Set.of("country", "messaging"), Set.of("worker.country", "worker.messaging.available", "worker.messaging.phone"))))) {
            var matching = matchingComposition.catalog();
            var redis = connection.sync();
            try {
                var time = redis.time();
                long sampled = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
                var facts = new LinkedHashMap<String, Map<String, String>>();
                for (int i = 0; i < 12; i++) {
                    String id = "w%02d".formatted(i);
                    redis.zadd(scope.keyspace().base() + ":worker:score:" + group, dueOrdinaryScore(sampled), id);
                    facts.put(id, Map.of("country", "US", "phone", "+1202555%04d".formatted(i), "messaging.enabled", "true"));
                }
                matchingComposition.properties().upsertWorkerFactsBatch(group, facts);
                var pacer = new WorkerEligibilityRefillPolicy(scores, matching, null, () -> sampled);
                assertThat(pacer.refill(List.of(group), List.of(countryTask))).isEqualTo(12);
                var before = readScores(redis, scope.keyspace(), group, List.copyOf(facts.keySet()));
                assertThat(before.values()).allMatch(score -> mark(score) == 1);
                assertThat(scores.observeDueHotScoreCandidates(group, null, 100)).isEmpty();

                commands.clear();
                assertThat(pacer.refill(List.of(group), List.of(countryTask, messagingTask))).isZero();
                // Only the aged and ordinary heads are read: no retained qualification or Score write.
                assertThat(commands.stream().filter("EVAL"::equals).count()).isEqualTo(2);
                assertThat(readScores(redis, scope.keyspace(), group, List.copyOf(facts.keySet()))).isEqualTo(before);
                assertThat(matching.take(group, Map.of("messaging", new WorkerQuery("worker.messaging.available",
                        Map.of("country", List.of("US")))))).isEmpty();

                commands.clear();
                var direct = matching.take(group, Map.of("direct", new WorkerQuery("worker.messaging.phone",
                        Map.of("phone", "+12025550000", "country", List.of("US"))))).get("direct");
                assertThat(commands).containsExactly("HMGET", "HMGET");
                assertThat(direct.workerId()).isEqualTo("w00");
                assertThat(direct.expectedScore()).isZero();
                var execution = scores.acquireCurrentHotScoreLeases(group, List.of(direct.workerId()), sampled + 30_000).get("w00");
                assertThat(execution.status()).isEqualTo(TRANSITIONED);
                assertThat(scores.acquireCurrentHotScoreLeases(group, List.of("w00"), sampled + 40_000).get("w00").status()).isEqualTo(STALE);
                // No Pool notification: its old entry is still counted and can still be taken.
                assertThat(matching.observeRefillDeficits(Map.of(group, List.of(country)))).containsEntry(group, 88);
                var cached = matching.take(group, Map.of("country", new WorkerQuery("worker.country", List.of("US")))).get("country");
                assertThat(cached.workerId()).isEqualTo("w00");
                assertThat(cached.expectedScore()).isEqualTo(before.get("w00"));
                var oldFence = Map.of("w00", cached.expectedScore());
                assertThat(scores.acquireObservedHotScoreLeases(group, oldFence, sampled + 30_000).get("w00").status()).isEqualTo(STALE);
                assertThat(scores.releaseObservedHotScoreHolds(group, oldFence, System.currentTimeMillis() + 100)
                        .get("w00").status()).isEqualTo(INVALID);
                assertThat(readScores(redis, scope.keyspace(), group, List.of("w00"))).containsEntry("w00", execution.score());
            } finally {
                scope.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"worker.phone", "worker.messaging.phone"})
    void poolAndDirectQueriesCompeteForOneExecutionLease(String directFunction) throws Exception {
        var scope = RedisTestScope.create("refill_direct_race");
        var client = RedisClient.create(REDIS_URL);
        String group = "g";
        var target = new RefillTarget("any", new EligibilityQuery(Map.of()), 1);
        var task = new TaskDescriptor("task", "test-project", group, TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), List.of(target), null, Map.of());
        try (var connection = client.connect();
                var scores = new RedisWorkerScoreCore(client, scope.keyspace());
                var matchingComposition = MatchingComposition.create(client, scope.keyspace(), Map.of(group,
                        new MatchingGroup(Set.of("any"), Set.of("worker.any", "worker.phone", "worker.messaging.phone"))))) {
            var matching = matchingComposition.catalog();
            var redis = connection.sync();
            try {
                var time = redis.time();
                long sampled = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
                redis.zadd(scope.keyspace().base() + ":worker:score:" + group, dueOrdinaryScore(sampled), "w");
                matchingComposition.properties().upsertWorkerFactsBatch(group, Map.of("w", Map.of("phone", "number", "country", "CN", "messaging.enabled", "true")));
                var pacer = new WorkerEligibilityRefillPolicy(scores, matching, null, () -> sampled);
                assertThat(pacer.refill(List.of(group), List.of(task))).isEqualTo(1);
                var pooled = matching.take(group, Map.of("pool", new WorkerQuery("worker.any", Map.of()))).get("pool");
                var direct = matching.take(group, Map.of("direct", new WorkerQuery(directFunction,
                        directFunction.equals("worker.phone") ? "number" : Map.of("phone", "number")))).get("direct");
                assertThat(direct.workerId()).isEqualTo(pooled.workerId());
                assertThat(direct.expectedScore()).isZero();
                try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var strict = executor.submit(() -> {
                        start.await();
                        return scores.acquireObservedHotScoreLeases(group, Map.of("w", pooled.expectedScore()), sampled + 30_000).get("w");
                    });
                    var current = executor.submit(() -> {
                        start.await();
                        return scores.acquireCurrentHotScoreLeases(group, List.of(direct.workerId()), sampled + 30_000).get("w");
                    });
                    start.countDown();
                    var results = List.of(strict.get(5, java.util.concurrent.TimeUnit.SECONDS), current.get(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertThat(results).extracting(result -> result.status()).containsExactlyInAnyOrder(TRANSITIONED, STALE);
                    long execution = results.stream().filter(result -> result.status() == TRANSITIONED).findFirst().orElseThrow().score();
                    assertThat(scores.releaseObservedHotScoreHolds(group, Map.of("w", pooled.expectedScore()), sampled + 100)
                            .get("w").status()).isNotEqualTo(TRANSITIONED);
                    assertThat(readScores(redis, scope.keyspace(), group, List.of("w"))).containsEntry("w", execution);
                }
            } finally {
                scope.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void recycledGenerationCanEnterAnotherPoolWhileTheOldEntryRemains() {
        var scope = RedisTestScope.create("refill_single_pool_generation");
        var client = RedisClient.create(REDIS_URL);
        String group = "g";
        var country = new RefillTarget("country", new EligibilityQuery(Map.of()), 1);
        var messaging = new RefillTarget("messaging", new EligibilityQuery(Map.of()), 1);
        var countryTask = new TaskDescriptor("country", "test-project", group, TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), List.of(country), null, Map.of());
        var messagingTask = new TaskDescriptor("messaging", "test-project", group, TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), List.of(messaging), null, Map.of());
        try (var connection = client.connect();
                var scores = new RedisWorkerScoreCore(client, scope.keyspace());
                var matchingComposition = MatchingComposition.create(client, scope.keyspace(), Map.of(group,
                        new MatchingGroup(Set.of("country", "messaging"), Set.of("worker.country", "worker.messaging.available"))))) {
            var matching = matchingComposition.catalog();
            var redis = connection.sync();
            try {
                var time = redis.time();
                long sampled = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
                redis.zadd(scope.keyspace().base() + ":worker:score:" + group, dueOrdinaryScore(sampled), "w");
                matchingComposition.properties().upsertWorkerFactsBatch(group, Map.of("w", Map.of("country", "US", "messaging.enabled", "true")));
                var clock = new java.util.concurrent.atomic.AtomicLong(sampled);
                var pacer = new WorkerEligibilityRefillPolicy(scores, matching, null, clock::get);
                assertThat(pacer.refill(List.of(group), List.of(countryTask))).isEqualTo(1);
                long original = readScores(redis, scope.keyspace(), group, List.of("w")).get("w");
                assertThat(pacer.refill(List.of(group), List.of(messagingTask))).isZero();
                clock.addAndGet(60_000);
                // A Redis slot may elapse between recycling and the ordinary-head read.
                int admitted = pacer.refill(List.of(group), List.of(messagingTask));
                assertThat(admitted).isBetween(0, 1);
                if (admitted == 0) {
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                            assertThat(scores.observeDueHotScoreCandidates(group, null, 1)).containsKey("w"));
                    assertThat(pacer.refill(List.of(group), List.of(messagingTask))).isEqualTo(1);
                }
                var old = matching.take(group, Map.of("old", new WorkerQuery("worker.country", List.of("US")))).get("old");
                var fresh = matching.take(group, Map.of("fresh", new WorkerQuery("worker.messaging.available", Map.of()))).get("fresh");
                assertThat(old.expectedScore()).isEqualTo(original);
                assertThat(fresh.workerId()).isEqualTo(old.workerId());
                assertThat(fresh.expectedScore()).isNotEqualTo(original);
                assertThat(scores.acquireObservedHotScoreLeases(group, Map.of("w", original), sampled + 30_000).get("w").status()).isEqualTo(STALE);
                assertThat(scores.acquireObservedHotScoreLeases(group, Map.of("w", fresh.expectedScore()), sampled + 30_000)
                        .get("w").status()).isEqualTo(TRANSITIONED);
            } finally {
                scope.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }

    @Test
    void reconnectReplenishesConsumedStaleStockWithoutAgedRecyclingOrFenceRevival() {
        var scope = RedisTestScope.create("refill_network_generation");
        var client = RedisClient.create(REDIS_URL);
        String group = "g";
        var targets = List.of(new RefillTarget("any", new EligibilityQuery(Map.of()), 1));
        var tasks = List.of(new TaskDescriptor("task", "test-project", group, TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "1"), targets, null, Map.of()));
        var query = Map.of("item", new WorkerQuery("worker.any", Map.of()));
        try (var connection = client.connect();
                var scores = new RedisWorkerScoreCore(client, scope.keyspace());
                var matchingComposition = MatchingComposition.create(client, scope.keyspace(),
                        Map.of(group, new MatchingGroup(Set.of("any"), Set.of("worker.any"))))) {
            var matching = matchingComposition.catalog();
            var redis = connection.sync();
            try {
                var parts = redis.time();
                long sampled = Long.parseLong(parts.get(0)) * 1000 + Long.parseLong(parts.get(1)) / 1000;
                long original = dueOrdinaryScore(sampled);
                redis.zadd(scope.keyspace().base() + ":worker:score:" + group, original, "w");
                // A fixed round clock excludes aged recycling from both production Refill calls.
                var pacer = new WorkerEligibilityRefillPolicy(scores, matching, null, () -> sampled);
                assertThat(pacer.refill(List.of(group), tasks)).isEqualTo(1);
                long oldFence = readScores(redis, scope.keyspace(), group, List.of("w")).get("w");
                assertThat(mark(oldFence)).isEqualTo(1);
                for (int poll = 0; poll < 3; poll++) {
                    var repeated = scores.rewriteCurrentPolarityWithinTimeFence(group, Map.of("w", sampled),
                            HOT_ACQUIRE, 0).get("w");
                    assertThat(repeated.status()).isEqualTo(NOOP);
                    assertThat(repeated.score()).isEqualTo(oldFence);
                    assertThat(matching.observeRefillDeficits(Map.of(group, targets))).isEmpty();
                }

                var offline = scores.rewriteCurrentPolarityWithinTimeFence(group,
                        Map.of("w", timeMillis(original)), RECOVERY_RECHECK, 0).get("w");
                assertThat(offline.status()).isEqualTo(TRANSITIONED);
                var consumed = matching.take(group, query).get("item");
                assertThat(consumed.expectedScore()).isEqualTo(oldFence);
                assertThat(scores.acquireObservedHotScoreLeases(group, Map.of("w", consumed.expectedScore()),
                        sampled + 30_000).get("w").status()).isEqualTo(STALE);
                assertThat(matching.take(group, query)).isEmpty();

                var connected = scores.rewriteCurrentPolarityWithinTimeFence(group,
                        Map.of("w", timeMillis(offline.score())), HOT_ACQUIRE, timeMillis(original)).get("w");
                assertThat(connected.status()).isEqualTo(TRANSITIONED);
                assertThat(mark(connected.score())).isZero();
                assertThat(timeMillis(connected.score())).isGreaterThan(timeMillis(oldFence));
                assertThat(scores.observeHotCandidateScoresBefore(group, null, sampled - 60_000, 100)).isEmpty();
                assertThat(pacer.refill(List.of(group), tasks)).isEqualTo(1);
                var fresh = matching.take(group, query).get("item");
                assertThat(fresh.workerId()).isEqualTo("w");
                assertThat(fresh.expectedScore()).isNotEqualTo(oldFence);
                assertThat(scores.acquireObservedHotScoreLeases(group, Map.of("w", oldFence), sampled + 30_000)
                        .get("w").status()).isEqualTo(STALE);
                assertThat(scores.releaseObservedHotScoreHolds(group, Map.of("w", oldFence), System.currentTimeMillis() + 100)
                        .get("w").status()).isNotEqualTo(TRANSITIONED);
                assertThat(readScores(redis, scope.keyspace(), group, List.of("w"))).containsEntry("w", fresh.expectedScore());
                assertThat(scores.acquireObservedHotScoreLeases(group, Map.of("w", fresh.expectedScore()), sampled + 30_000)
                        .get("w").status()).isEqualTo(TRANSITIONED);
            } finally {
                scope.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {80, 99})
    void observedShortageLeavesTheOtherOrdinaryWorkersAvailableForTheNextRound(int residentCount) {
        int deficit = 100 - residentCount;
        var scope=RedisTestScope.create("refill_deficit");
        var client=RedisClient.create(REDIS_URL);
        var commands=new CopyOnWriteArrayList<String>();
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        String group="g";
        var target=new RefillTarget("any",new EligibilityQuery(Map.of()),100);
        var declarations=List.of(target);
        var tasks=List.of(new TaskDescriptor("task","test-project",group,TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority","0","maxRetryTimes","1"),declarations,null,Map.of()));
        try (var connection=client.connect();
                var scores=new RedisWorkerScoreCore(client,scope.keyspace());
                var matchingComposition=MatchingComposition.create(client,scope.keyspace(),
                        Map.of(group,new MatchingGroup(Set.of("any"),Set.of("worker.any"))))) {
            var matching = matchingComposition.catalog();
            var redis=connection.sync();
            try {
                var time=redis.time();
                long now=Long.parseLong(time.get(0))*1000+Long.parseLong(time.get(1))/1000;
                long original=dueOrdinaryScore(now);
                String key=scope.keyspace().base()+":worker:score:"+group;
                var residents=new LinkedHashMap<String,Long>();
                for(int i=0;i<residentCount;i++) {
                    String id="resident-"+i;
                    redis.zadd(key,original,id); residents.put(id,original);
                }
                var held=new LinkedHashMap<String,Long>();
                scores.candidateizeObservedHotScores(group,residents).forEach((id,result)->{
                    assertThat(result.status()).isEqualTo(TRANSITIONED);
                    held.put(id,result.score());
                });
                assertThat(matching.refill(group,declarations,held)).isEqualTo(residentCount);
                var additional=new LinkedHashMap<String,Long>();
                for(int i=0;i<100;i++) {
                    String id="available-%03d".formatted(i);
                    redis.zadd(key,original,id); additional.put(id,original);
                }
                assertThat(matching.observeRefillDeficits(Map.of(group,declarations))).containsExactlyEntriesOf(Map.of(group,deficit));
                var pacer=new WorkerEligibilityRefillPolicy(scores,matching,null,()->now);
                commands.clear();
                assertThat(pacer.refill(List.of(group),tasks)).isEqualTo(deficit);
                assertThat(Collections.frequency(commands,"EVAL")).isEqualTo(3); // old head + due head + exact candidateize
                assertThat(commands).doesNotContain("ZMSCORE","TIME");

                var current=readScores(redis,scope.keyspace(),group,List.copyOf(additional.keySet()));
                var unchanged=new LinkedHashMap<String,Long>();
                var changed=new ArrayList<String>();
                current.forEach((id,score)->{
                    if(score.longValue()==original) unchanged.put(id,score);
                    else {
                        changed.add(id);
                        assertThat(mark(score)).isEqualTo(1);
                        assertThat(timeMillis(score)).isEqualTo(timeMillis(original));
                    }
                });
                assertThat(changed).hasSize(deficit);
                assertThat(unchanged).hasSize(residentCount);
                assertThat(scores.observeDueHotScoreCandidates(group,null,100)).containsExactlyInAnyOrderEntriesOf(unchanged);
                assertThat(scores.observeHotCandidateScoresBefore(group,null,now-60_000,100)).isEmpty();

                var queries=new LinkedHashMap<String,WorkerQuery>();
                for(int i=0;i<100;i++)queries.put("item-"+i,new WorkerQuery("worker.any",Map.of()));
                var taken=matching.take(group,queries);
                assertThat(taken).hasSize(100);
                assertThat(taken.values()).extracting(candidate->candidate.workerId())
                        .containsAll(residents.keySet()).containsAll(changed);
                commands.clear();
                assertThat(pacer.refill(List.of(group),tasks)).isEqualTo(residentCount);
                assertThat(Collections.frequency(commands,"EVAL")).isEqualTo(3);
                var next=matching.take(group,queries);
                assertThat(next.values()).extracting(candidate->candidate.workerId())
                        .containsExactlyInAnyOrderElementsOf(unchanged.keySet());
                assertThat(scores.observeDueHotScoreCandidates(group,null,100)).isEmpty();
                assertThat(scores.observeHotCandidateScoresBefore(group,null,now-60_000,100)).isEmpty();
            } finally {
                scope.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }
}
