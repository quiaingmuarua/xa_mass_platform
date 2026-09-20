package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.*;
import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class WorkerNetworkGenerationIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerScoreCore scores;
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach void open() {
        scope = RedisTestScope.create("network_generation");
        client = RedisClient.create(REDIS_URL);
        client.addListener(new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        connection = client.connect();
        redis = connection.sync();
        scores = new RedisWorkerScoreCore(client, scope.keyspace());
        scores.observeSchedulingStates("warm", List.of("absent"));
    }

    @AfterEach void close() {
        scope.cleanup(redis);
        scores.close();
        connection.close();
        client.shutdown();
    }

    private String key() { return scope.keyspace().base() + ":worker:score:g"; }
    private long now() {
        var parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1000 + Long.parseLong(parts.get(1)) / 1000;
    }
    private long put(String id, int sign, long slot, int mark) {
        long score = sign * absoluteScore(slot, mark);
        redis.zadd(key(), score, id);
        return score;
    }

    @Test void pastPolarityBatchUsesEvidenceAndAcceptsSameSlotWithoutCreatingFutureTime() {
        for (var target : WorkerScorePolarity.values()) {
            long before = now() / SLOT_MILLIS;
            long oldSlot = before - 100;
            var evidence = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 100; i++) {
                String id = "w" + i;
                put(id, -polarityValue(target), oldSlot, i % 2);
                long evidenceSlot = switch (i % 3) {
                    case 0 -> oldSlot;
                    case 1 -> oldSlot + 20;
                    default -> before + 1000;
                };
                evidence.put(id, evidenceSlot * SLOT_MILLIS);
            }
            commands.clear();
            var result = scores.rewriteCurrentPolarityWithinTimeFence("g", evidence, target, 0);
            assertThat(commands).containsExactly("EVAL");
            long after = now() / SLOT_MILLIS;
            Long batchNow = null;
            for (int i = 0; i < 100; i++) {
                var changed = result.get("w" + i);
                assertThat(changed.status()).isEqualTo(TRANSITIONED);
                var state = decodeState("w" + i, changed.score());
                assertThat(state.mark()).isZero();
                assertThat(state.polarity()).isEqualTo(target);
                long slot = state.timeMillis() / SLOT_MILLIS;
                if (i % 3 == 0) assertThat(slot).isEqualTo(oldSlot + 1);
                else if (i % 3 == 1) assertThat(slot).isEqualTo(oldSlot + 20);
                else {
                    assertThat(slot).isBetween(before, after);
                    if (batchNow == null) batchNow = slot;
                    else assertThat(slot).isEqualTo(batchNow);
                }
            }
        }
    }

    @Test void repeatedSamePolarityObservationsKeepNormalCandidateAndRecoveryCoordinates() {
        long oldSlot = now() / SLOT_MILLIS - 20;
        for (var target : WorkerScorePolarity.values()) {
            var originals = new LinkedHashMap<String, Long>();
            for (int mark : new int[]{0, 1}) {
                String id = target + "-" + mark;
                originals.put(id, put(id, polarityValue(target), oldSlot, mark));
            }
            for (int poll = 0; poll < 3; poll++) {
                var evidence = new LinkedHashMap<String, Long>();
                originals.keySet().forEach(id -> evidence.put(id, now()));
                var results = scores.rewriteCurrentPolarityWithinTimeFence("g", evidence, target,
                        target == WorkerScorePolarity.HOT_ACQUIRE ? (oldSlot - 10) * SLOT_MILLIS : 0);
                originals.forEach((id, original) -> {
                    assertThat(results.get(id)).isEqualTo(new WorkerScoreTransitionResult(NOOP, original));
                    assertThat(redis.zscore(key(), id)).isEqualTo(original.doubleValue());
                });
            }
        }
    }

    @Test void startupActivationIsTheOnlySamePolarityRefreshAndRequiresPostFloorEvidence() {
        long before = now() / SLOT_MILLIS;
        long floor = before - 20;
        for (int sign : new int[]{-1, 1}) {
            String id = "w" + sign;
            long old = put(id, sign, floor - 10, 1);
            var rejected = scores.rewriteCurrentPolarityWithinTimeFence("g",
                    Map.of(id, (floor - 1) * SLOT_MILLIS), WorkerScorePolarity.HOT_ACQUIRE,
                    floor * SLOT_MILLIS).get(id);
            assertThat(rejected).isEqualTo(new WorkerScoreTransitionResult(STALE, old));
            assertThat(redis.zscore(key(), id)).isEqualTo((double) old);
            var activated = scores.rewriteCurrentPolarityWithinTimeFence("g",
                    Map.of(id, (floor + 1) * SLOT_MILLIS), WorkerScorePolarity.HOT_ACQUIRE,
                    floor * SLOT_MILLIS).get(id);
            assertThat(activated).isEqualTo(new WorkerScoreTransitionResult(TRANSITIONED, floor + 1));
            assertThat(scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of(id, now()),
                    WorkerScorePolarity.HOT_ACQUIRE, floor * SLOT_MILLIS).get(id))
                    .isEqualTo(new WorkerScoreTransitionResult(NOOP, floor + 1));
        }
    }

    @Test void delayedEvidenceChecksTheCurrentGenerationBeforeChangingIt() {
        long slot = now() / SLOT_MILLIS - 10;
        long candidate = put("w", 1, slot, 1);
        var stale = scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("w", (slot - 1) * SLOT_MILLIS),
                WorkerScorePolarity.RECOVERY_RECHECK, 0).get("w");
        assertThat(stale).isEqualTo(new WorkerScoreTransitionResult(STALE, candidate));
        assertThat(redis.zscore(key(), "w")).isEqualTo((double) candidate);
        assertThat(scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("missing", now()),
                WorkerScorePolarity.HOT_ACQUIRE, 0).get("missing"))
                .isEqualTo(new WorkerScoreTransitionResult(STALE, null));
        assertThat(redis.zscore(key(), "missing")).isNull();
    }

    @Test void networkChangeAndAssignmentPreserveBothSerializedOrdersAndResultAssociation() {
        long sampled = now();
        long oldSlot = sampled / SLOT_MILLIS - 20;
        long evidence = (oldSlot + 10) * SLOT_MILLIS;
        long first = put("network-first", 1, oldSlot, 1);
        long second = put("assignment-first", 1, oldSlot, 1);

        var changed = scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("network-first", evidence),
                WorkerScorePolarity.RECOVERY_RECHECK, 0).get("network-first");
        assertThat(changed.status()).isEqualTo(TRANSITIONED);
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("network-first", first), sampled + 30_000)
                .get("network-first").status()).isEqualTo(STALE);
        assertThat(redis.zscore(key(), "network-first")).isEqualTo(changed.score().doubleValue());

        var execution = scores.acquireObservedHotScoreLeases("g", Map.of("assignment-first", second),
                sampled + 30_000).get("assignment-first");
        assertThat(execution.status()).isEqualTo(TRANSITIONED);
        var disconnected = scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("assignment-first", evidence),
                WorkerScorePolarity.RECOVERY_RECHECK, 0).get("assignment-first");
        assertThat(disconnected.score()).isEqualTo(-execution.score());
        var connected = scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("assignment-first", now()),
                WorkerScorePolarity.HOT_ACQUIRE, 0).get("assignment-first");
        assertThat(connected.score()).isEqualTo(execution.score());
        scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("assignment-first", now()),
                WorkerScorePolarity.RECOVERY_RECHECK, 0);
        assertThat(scores.releaseObservedHotScoreHolds("g", Map.of("assignment-first", execution.score()), now() + 100)
                .get("assignment-first").status()).isEqualTo(TRANSITIONED);
    }

    @Test void exactToggleClearsMarkWithoutChangingTimeOrSamplingTime() {
        long slot = now() / SLOT_MILLIS;
        for (int sign : new int[]{-1, 1}) {
            for (int mark : new int[]{0, 1}) {
                for (long time : new long[]{slot - 10, slot + 100, MAX_TIME_SLOT}) {
                    long old = put("w", sign, time, mark);
                    commands.clear();
                    var changed = scores.toggleCurrentPolarity("g", "w", old);
                    assertThat(commands).containsExactly("EVAL");
                    assertThat(changed).isEqualTo(new WorkerScoreTransitionResult(TRANSITIONED, -sign * time));
                    assertThat(scores.toggleCurrentPolarity("g", "w", old).status()).isEqualTo(STALE);
                }
            }
        }
    }

    @Test void generationRefreshDoesNotRepairFractionalStoredCoordinates() {
        long slot = now() / SLOT_MILLIS - 20;
        redis.zadd(key(), absoluteScore(slot, 1) + 0.5, "fractional");
        scores.rewriteCurrentPolarityWithinTimeFence("g", Map.of("fractional", (slot + 10) * SLOT_MILLIS),
                WorkerScorePolarity.RECOVERY_RECHECK, 0);
        assertThat(redis.zscore(key(), "fractional")).isEqualTo(-(slot + 10 + 0.5));
    }
}
