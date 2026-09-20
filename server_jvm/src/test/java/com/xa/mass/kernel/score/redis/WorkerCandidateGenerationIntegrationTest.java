package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;
import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class WorkerCandidateGenerationIntegrationTest {
    RedisTestScope scope;
    RedisClient client;
    StatefulRedisConnection<String, String> connection;
    RedisCommands<String, String> redis;
    RedisWorkerScoreCore scores;
    final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach void open() {
        scope = RedisTestScope.create("candidate_generation");
        client = RedisClient.create(REDIS_URL);
        client.addListener(new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        connection = client.connect(); redis = connection.sync();
        scores = new RedisWorkerScoreCore(client, scope.keyspace());
    }
    @AfterEach void close() {
        scope.cleanup(redis); scores.close(); connection.close(); client.shutdown();
    }
    String key(String group) { return scope.keyspace().base() + ":worker:score:" + group; }
    long now() {
        var time = redis.time(); return Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
    }
    long put(String group, String id, int sign, long slot, int mark) {
        long score = sign * absoluteScore(slot, mark); redis.zadd(key(group), score, id); return score;
    }

    @Test void allFourHeadsAdvanceSameTimeMembersWithoutCursors() {
        long time = now() / 100 - 1000;
        for (String group : List.of("ordinary", "candidate", "hot", "recovery")) {
            int mark = group.equals("candidate") ? 1 : 0;
            for (int i = 0; i < 250; i++) put(group, "w%03d".formatted(i), group.equals("recovery") ? -1 : 1, time, mark);
            for (int expected : List.of(100, 100, 50)) {
                Map<String, Long> batch = switch (group) {
                    case "ordinary" -> scores.observeDueHotScoreCandidates(group, time * 100, 100);
                    case "candidate" -> scores.observeHotCandidateScoresBefore(group, time * 100, (time + 1) * 100, 100);
                    case "hot" -> scores.observeHotCandidatesBefore(group, (time + 1) * 100, 100);
                    default -> scores.observeRecoveryRecheckCandidates(group, 100);
                };
                assertThat(batch).hasSize(expected);
                var changed = switch (group) {
                    case "ordinary" -> scores.candidateizeObservedHotScores(group, batch);
                    case "candidate" -> scores.recycleObservedHotCandidates(group, batch);
                    default -> scores.deferObservedToRecovery(group, batch, 15_000);
                };
                assertThat(changed.values()).allSatisfy(result -> assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));
                if (group.equals("ordinary")) changed.forEach((id, result) -> {
                    var state = decodeState(id, result.score());
                    assertThat(state.timeMillis()).isEqualTo(time * 100);
                    assertThat(state.mark()).isEqualTo(1);
                });
                if (group.equals("candidate")) changed.forEach((id, result) -> {
                    assertThat(decodeState(id, result.score()).timeMillis()).isGreaterThan(time * 100);
                    assertThat(scores.recycleObservedHotCandidates(group, Map.of(id, batch.get(id))).get(id).status())
                            .isEqualTo(WorkerScoreTransitionStatus.STALE);
                });
            }
        }
    }

    @Test void propertiesMakeHotCandidatesRefillableWithoutRecyclingAndNeverReviveOldFences() throws Exception {
        long time = now() / 100 - 10;
        long original = put("g", "w", 1, time, 0);
        long candidate = scores.candidateizeObservedHotScores("g", Map.of("w", original)).get("w").score();
        var invalidated = scores.advancePastScoreTimesToNow("g", List.of("w")).get("w");
        assertThat(invalidated.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(decodeState("w", invalidated.score()).mark()).isZero();
        assertThat(decodeState("w", invalidated.score()).timeMillis()).isGreaterThan(time * SLOT_MILLIS);
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w", candidate), now() + 5000).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);

        // Repeated invalidation cannot re-create the old candidate, even in the same slot.
        var repeated = scores.advancePastScoreTimesToNow("g", List.of("w")).get("w");
        assertThat(repeated.status()).isIn(WorkerScoreTransitionStatus.NOOP, WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(decodeState("w", repeated.score()).mark()).isZero();
        assertThat(repeated.score()).isGreaterThanOrEqualTo(invalidated.score());
        long after = decodeState("w", repeated.score()).timeMillis() / SLOT_MILLIS;
        // Only wait for the next slot; no candidate recycling operation is needed.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (now() / SLOT_MILLIS <= after && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(now() / SLOT_MILLIS).isGreaterThan(after);
        var due = scores.observeDueHotScoreCandidates("g", null, 100);
        assertThat(due).containsExactlyEntriesOf(Map.of("w", repeated.score()));
        var renewed = scores.candidateizeObservedHotScores("g", due).get("w");
        assertThat(renewed.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(decodeState("w", renewed.score()).mark()).isEqualTo(CANDIDATE_MARK);
        assertThat(renewed.score()).isNotEqualTo(candidate);
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w", candidate), now() + 5000).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scores.releaseObservedHotScoreHolds("g", Map.of("w", candidate), now()).get("w").status())
                .isNotEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(redis.zscore(key("g"), "w")).isEqualTo(renewed.score().doubleValue());

        var acquired = scores.acquireObservedHotScoreLeases("g", Map.of("w", renewed.score()), now() + 5000).get("w");
        assertThat(acquired.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(decodeState("w", acquired.score()).mark()).isZero();
        assertThat(scores.advancePastScoreTimesToNow("g", List.of("w")).get("w"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, acquired.score()));
    }

    @Test void propertiesAndAssignmentRacePreservesTheWinningFence() throws Exception {
        long candidate = put("g", "w", 1, now() / SLOT_MILLIS - 10, CANDIDATE_MARK);
        long target = now() + 30_000;
        var start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(client, scope.keyspace());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invalidation = executor.submit(() -> {
                start.await();
                return scores.advancePastScoreTimesToNow("g", List.of("w")).get("w");
            });
            var assignment = executor.submit(() -> {
                start.await();
                return competing.acquireObservedHotScoreLeases("g", Map.of("w", candidate), target).get("w");
            });
            start.countDown();
            var changed = invalidation.get(5, TimeUnit.SECONDS);
            var acquired = assignment.get(5, TimeUnit.SECONDS);
            if (acquired.status() == WorkerScoreTransitionStatus.TRANSITIONED) {
                assertThat(changed).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, acquired.score()));
                assertThat(redis.zscore(key("g"), "w")).isEqualTo(acquired.score().doubleValue());
            } else {
                assertThat(acquired.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
                assertThat(changed.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
                assertThat(redis.zscore(key("g"), "w")).isEqualTo(changed.score().doubleValue());
            }
            assertThat(decodeState("w", changed.score()).mark()).isZero();
        }
    }

    @Test void directAndTwoPoolFencesCompeteForOnlyOneExecution() throws Exception {
        long original = put("g", "w", 1, now() / 100 - 20, 0);
        long candidate = scores.candidateizeObservedHotScores("g", Map.of("w", original)).get("w").score();
        long deadline = now() + 5000;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<WorkerScoreTransitionResult>>();
            for (int i = 0; i < 3; i++) {
                boolean direct = i == 2;
                requests.add(executor.submit(() -> {
                    start.await();
                    return (direct ? scores.acquireCurrentHotScoreLeases("g", List.of("w"), deadline)
                            : scores.acquireObservedHotScoreLeases("g", Map.of("w", candidate), deadline)).get("w");
                }));
            }
            start.countDown();
            var results = new ArrayList<WorkerScoreTransitionResult>();
            for (var request : requests) results.add(request.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.status() == WorkerScoreTransitionStatus.TRANSITIONED)).hasSize(1);
            long execution = results.stream().filter(r -> r.status() == WorkerScoreTransitionStatus.TRANSITIONED).findFirst().orElseThrow().score();
            assertThat(scores.releaseObservedHotScoreHolds("g", Map.of("w", candidate), now()).get("w").status())
                    .isNotEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(redis.zscore(key("g"), "w")).isEqualTo((double) execution);
        }
    }

    @Test void propertiesKeepColdRecoveryAvailableForDelayedInitialConnection() throws Exception {
        long evidenceTime = now() - 1_000;
        long floor = evidenceTime - 1_000;
        scores.initializeRegisteredScores("g", List.of("ordinary-cold", "connected-first"));
        long candidateCold = put("g", "candidate-cold", -1, COLD_PARK_TIME_SLOT, CANDIDATE_MARK);
        scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("connected-first", evidenceTime),
                WorkerScorePolarity.HOT_ACQUIRE, floor);

        var advanced = scores.advancePastScoreTimesToNow("g",
                List.of("ordinary-cold", "candidate-cold", "connected-first"));
        assertThat(advanced.get("ordinary-cold"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, -COLD_PARK_TIME_SLOT));
        assertThat(advanced.get("candidate-cold"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, candidateCold));
        assertThat(advanced.get("connected-first").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        long propertySlot = decodeState("connected-first", advanced.get("connected-first").score())
                .timeMillis() / SLOT_MILLIS;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((now() / SLOT_MILLIS <= propertySlot || System.currentTimeMillis() / SLOT_MILLIS <= propertySlot)
                && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(now() / SLOT_MILLIS).isGreaterThan(propertySlot);
        assertThat(System.currentTimeMillis() / SLOT_MILLIS).isGreaterThan(propertySlot);

        var activated = scores.rewriteCurrentPolarityWithinTimeFence("g",
                Map.of("ordinary-cold", evidenceTime, "candidate-cold", evidenceTime),
                WorkerScorePolarity.HOT_ACQUIRE, floor);
        assertThat(activated.values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));
        assertThat(decodeState("candidate-cold", activated.get("candidate-cold").score()).mark()).isEqualTo(CANDIDATE_MARK);
        assertThat(scores.observeSchedulingStates("g", List.of("ordinary-cold", "candidate-cold", "connected-first"))
                .statesByWorkerId().values()).containsOnly(SchedulingState.HOT_SCORE_OVERDUE);
    }

    @Test void nonColdPropertiesAdvancementStillFencesOutOlderPendingConnectedEvidence() throws Exception {
        long evidenceTime = now() - 1_000;
        long floor = evidenceTime - 1_000;
        put("g", "properties-first", -1, evidenceTime / SLOT_MILLIS, ORDINARY_MARK);
        put("g", "connected-first", -1, evidenceTime / SLOT_MILLIS, ORDINARY_MARK);
        assertThat(scores.rewriteCurrentPolarityWithinTimeFence("g",
                Map.of("connected-first", evidenceTime), WorkerScorePolarity.HOT_ACQUIRE, floor)
                .get("connected-first").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);

        var changed = scores.advancePastScoreTimesToNow("g", List.of("properties-first", "connected-first"));
        long propertySlot = decodeState("properties-first", changed.get("properties-first").score())
                .timeMillis() / SLOT_MILLIS;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((now() / SLOT_MILLIS <= propertySlot || System.currentTimeMillis() / SLOT_MILLIS <= propertySlot)
                && System.nanoTime() < deadline) Thread.sleep(1);
        assertThat(now() / SLOT_MILLIS).isGreaterThan(propertySlot);
        assertThat(System.currentTimeMillis() / SLOT_MILLIS).isGreaterThan(propertySlot);

        // The cold exception does not relax the existing evidence-time fence for
        // ordinary RECOVERY coordinates, which still need sufficiently new evidence.
        assertThat(scores.rewriteCurrentPolarityWithinTimeFence("g",
                Map.of("properties-first", evidenceTime), WorkerScorePolarity.HOT_ACQUIRE, floor)
                .get("properties-first").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scores.observeSchedulingStates("g", List.of("properties-first", "connected-first"))
                .statesByWorkerId()).containsEntry("properties-first", SchedulingState.RECOVERY)
                .containsEntry("connected-first", SchedulingState.HOT_SCORE_OVERDUE);
        assertThat(scores.rewriteCurrentPolarityWithinTimeFence("g",
                Map.of("properties-first", now()), WorkerScorePolarity.HOT_ACQUIRE, floor)
                .get("properties-first").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test void logicalTimeOrderSpansBothMarkRangesAndRawBudgetDoesNotRefill() {
        long time = now() / 100 - 100;
        put("hot", "older-candidate", 1, time - 1, 1);
        put("hot", "newer-ordinary", 1, time, 0);
        assertThat(scores.observeHotCandidatesBefore("hot", (time + 1) * 100, 1).keySet()).containsExactly("newer-ordinary");
        put("recovery", "older-candidate", -1, time - 1, 1);
        put("recovery", "newer-ordinary", -1, time, 0);
        put("recovery", "cold-candidate", -1, 1, 1);
        assertThat(scores.observeRecoveryRecheckCandidates("recovery", 1).keySet()).containsExactly("older-candidate");
        put("ordinary", "good", 1, time, 0);
        redis.zadd(key("ordinary"), time - 0.5, "bad");
        assertThat(scores.observeDueHotScoreCandidates("ordinary", null, 1)).isEmpty();
        assertThat(scores.observeDueHotScoreCandidates("ordinary", null, 2).keySet()).containsExactly("good");
    }

    @Test void pauseAndRecoveryFutureWritesAlwaysLeaveTheCandidateLane() {
        long time = now() / 100 - 10;
        put("g", "pause", -1, time, 1);
        assertThat(scores.pauseScheduling("g", "pause")).isEqualTo(WorkerSchedulingChangeStatus.APPLIED);
        assertThat(redis.zscore(key("g"), "pause")).isEqualTo((double) -MAX_TIME_SLOT);
        assertThat(scores.pauseScheduling("g", "pause")).isEqualTo(WorkerSchedulingChangeStatus.UNCHANGED);
        long candidate = put("g", "recheck", 1, time, 1);
        var deferred = scores.deferObservedToRecovery("g", Map.of("recheck", candidate), 15_000).get("recheck");
        assertThat(deferred.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(decodeState("recheck", deferred.score()).mark()).isZero();
        assertThat(scores.observeRecoveryRecheckCandidates("g", 100)).isEmpty();
    }

    @Test void candidateMutationsRemainOneEvalPerHundredMembersWithoutPointReads() {
        scores.initializeRegisteredScores("warm", List.of("w"));
        long time = now() / SLOT_MILLIS - 100;
        var ordinary = new LinkedHashMap<String, Long>();
        var candidates = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 100; i++) {
            String id = "w" + i;
            ordinary.put(id, put("ordinary", id, 1, time, 0));
            candidates.put(id, put("candidate", id, 1, time, 1));
            put("properties", id, i % 2 == 0 ? 1 : -1, time, (i / 2) % 2);
        }
        commands.clear();
        for (var results : List.of(
                scores.candidateizeObservedHotScores("ordinary", ordinary),
                scores.recycleObservedHotCandidates("candidate", candidates),
                scores.advancePastScoreTimesToNow("properties", List.copyOf(ordinary.keySet())))) {
            assertThat(results).hasSize(100);
            assertThat(results.values()).allSatisfy(result ->
                    assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));
        }
        assertThat(commands).containsExactly("EVAL", "EVAL", "EVAL");
    }

    @Test void changedMissingOrFutureObservationsCannotCandidateizeOrReturnAnExecutionFence() {
        long time = now() / SLOT_MILLIS - 10;
        var observed = new LinkedHashMap<String, Long>();
        for (String id : List.of("deleted", "polarity", "generation", "lane", "paused", "future")) {
            observed.put(id, put("g", id, 1, time, 0));
        }
        redis.zrem(key("g"), "deleted");
        put("g", "polarity", -1, time, 0);
        put("g", "generation", 1, time + 1, 0);
        put("g", "lane", 1, time, 1);
        scores.pauseScheduling("g", "paused");
        observed.put("future", put("g", "future", 1, time + 1000, 0));
        assertThat(scores.candidateizeObservedHotScores("g", observed).values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
        assertThat(scores.observeHotCandidateScoresBefore("g", null, time * SLOT_MILLIS + 100, 100))
                .containsOnlyKeys("lane");
    }
}
