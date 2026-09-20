package com.xa.mass.kernel.pacer.dispatch;

import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.STALE;
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
                var matching = MatchingComposition.create(client, scope.keyspace(),
                        Map.of(group, new MatchingGroup(Set.of("any"), Set.of("worker.any"))))) {
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

    @Test
    void oneMissingEntryLeavesTheOtherOrdinaryWorkersAvailableForTheNextRound() {
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
                var matching=MatchingComposition.create(client,scope.keyspace(),
                        Map.of(group,new MatchingGroup(Set.of("any"),Set.of("worker.any"))))) {
            var redis=connection.sync();
            try {
                var time=redis.time();
                long now=Long.parseLong(time.get(0))*1000+Long.parseLong(time.get(1))/1000;
                long original=dueOrdinaryScore(now);
                String key=scope.keyspace().base()+":worker:score:"+group;
                var residents=new LinkedHashMap<String,Long>();
                for(int i=0;i<99;i++) {
                    String id="resident-"+i;
                    redis.zadd(key,original,id); residents.put(id,original);
                }
                var held=new LinkedHashMap<String,Long>();
                scores.candidateizeObservedHotScores(group,residents).forEach((id,result)->{
                    assertThat(result.status()).isEqualTo(TRANSITIONED);
                    held.put(id,result.score());
                });
                assertThat(matching.refill(group,declarations,held)).isEqualTo(99);
                var additional=new LinkedHashMap<String,Long>();
                for(int i=0;i<100;i++) {
                    String id="available-%03d".formatted(i);
                    redis.zadd(key,original,id); additional.put(id,original);
                }
                assertThat(matching.observeRefillDeficits(Map.of(group,declarations))).containsExactlyEntriesOf(Map.of(group,1));
                var pacer=new WorkerEligibilityRefillPolicy(scores,matching,null,()->now);
                commands.clear();
                assertThat(pacer.refill(List.of(group),tasks)).isEqualTo(1);
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
                assertThat(changed).hasSize(1);
                assertThat(unchanged).hasSize(99);
                assertThat(scores.observeDueHotScoreCandidates(group,null,100)).containsExactlyInAnyOrderEntriesOf(unchanged);
                assertThat(scores.observeHotCandidateScoresBefore(group,null,now-60_000,100)).isEmpty();

                var queries=new LinkedHashMap<String,WorkerQuery>();
                for(int i=0;i<100;i++)queries.put("item-"+i,new WorkerQuery("worker.any",Map.of()));
                var taken=matching.take(group,queries);
                assertThat(taken).hasSize(100);
                assertThat(taken.values()).extracting(candidate->candidate.workerId())
                        .containsAll(residents.keySet()).contains(changed.getFirst());
                commands.clear();
                assertThat(pacer.refill(List.of(group),tasks)).isEqualTo(99);
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
