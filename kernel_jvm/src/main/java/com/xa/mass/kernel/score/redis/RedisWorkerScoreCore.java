package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Worker Score Redis provider. Java owns the clock, every coordinate, range bound and target;
 * the fixed scripts only read, compare and write, and never interpret the encoding.
 */
public final class RedisWorkerScoreCore
        implements WorkerScoreCore, AutoCloseable {

    /** Tuples (id, expected, target, counterpart|''): exact match writes the Java target. */
    private static final String EXACT_CAS_SCRIPT = """
            local results = {}
            for i = 1, #ARGV, 4 do
              local id, status, echo = ARGV[i], 'stale', ''
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if stored then
                local current = tonumber(stored)
                echo = stored
                if current == tonumber(ARGV[i + 1])
                    or (ARGV[i + 3] ~= '' and current == tonumber(ARGV[i + 3])) then
                  if current == tonumber(ARGV[i + 2]) then
                    status = 'noop'
                  else
                    redis.call('ZADD', KEYS[1], ARGV[i + 2], id)
                    status, echo = 'transitioned', ARGV[i + 2]
                  end
                end
              end
              results[#results + 1] = id
              results[#results + 1] = status
              results[#results + 1] = echo
            end
            return results
            """;

    /**
     * ARGV: ruleTableCount, then per table ruleCount followed by (min, max, action, value)
     * rules, then (id, tableIndex) members. The first inclusive interval containing the
     * stored integer decides: w writes value, s writes value * |stored|, n is noop, t is
     * stale. Non-integer or unmatched values are invalid and never written.
     */
    private static final String INTERVAL_REWRITE_SCRIPT = """
            local pos, tables = 2, {}
            for t = 1, tonumber(ARGV[1]) do
              local rules = {}
              for r = 1, tonumber(ARGV[pos]) do
                local at = pos + 1 + (r - 1) * 4
                rules[r] = {tonumber(ARGV[at]), tonumber(ARGV[at + 1]), ARGV[at + 2], ARGV[at + 3]}
              end
              pos = pos + 1 + #rules * 4
              tables[t] = rules
            end
            local results = {}
            while pos <= #ARGV do
              local id, rules = ARGV[pos], tables[tonumber(ARGV[pos + 1])]
              pos = pos + 2
              local status, echo = 'stale', ''
              local stored = redis.call('ZSCORE', KEYS[1], id)
              if stored then
                local current = tonumber(stored)
                status = 'invalid'
                if current and current == math.floor(current) then
                  for _, rule in ipairs(rules) do
                    if current >= rule[1] and current <= rule[2] then
                      local target = nil
                      echo = stored
                      if rule[3] == 'w' then
                        target = rule[4]
                      elseif rule[3] == 's' then
                        target = string.format('%.0f', tonumber(rule[4]) * math.abs(current))
                      elseif rule[3] == 'n' then
                        status = 'noop'
                      else
                        status = 'stale'
                      end
                      if target then
                        if tonumber(target) == current then
                          status = 'noop'
                        else
                          redis.call('ZADD', KEYS[1], target, id)
                          status, echo = 'transitioned', target
                        end
                      end
                      break
                    end
                  end
                end
              end
              results[#results + 1] = id
              results[#results + 1] = status
              results[#results + 1] = echo
            end
            return results
            """;

    private static final String INITIALIZE_REGISTERED_SCRIPT = """
            local created = {}
            for i = 2, #ARGV do
              if redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[i]) == 1 then
                created[#created + 1] = ARGV[i]
              end
            end
            return created
            """;

    private static final String WRITE = "w";
    private static final String SIGN = "s";
    private static final String NOOP = "n";
    private static final String STALE = "t";

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private final LongSupplier currentTimeMillis;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerScoreCore(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this(redisClient, keyspace, System::currentTimeMillis);
    }

    public RedisWorkerScoreCore(
            RedisClient redisClient,
            RedisKeyspace keyspace,
            LongSupplier currentTimeMillis
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        this.redisClient = redisClient;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public WorkerSchedulingObservation observeSchedulingStates(
            String homeBucketId,
            List<String> workerIds
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > MAX_SCORE_BATCH_SIZE
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique values");
        }
        workerIds.forEach(workerId ->
                requireNonBlank(workerId, "workerId"));
        Map<String, WorkerScoreState> states = readScoreStates(homeBucketId, workerIds);
        long readAtMillis = currentTimeMillis.getAsLong();
        var projected = new LinkedHashMap<String, SchedulingState>();
        states.forEach((id, state) -> projected.put(id, schedulingState(state, readAtMillis)));
        return new WorkerSchedulingObservation(readAtMillis, projected);
    }

    @Override
    public WorkerSchedulingChangeStatus pauseScheduling(String homeBucketId, String workerId) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        var rules = new IntervalRules()
                .add(1, MAX_ABSOLUTE_SCORE, WRITE, score(1, ORDINARY_MARK, PAUSE_TIME_SLOT))
                .add(-MAX_ABSOLUTE_SCORE, -1, WRITE, score(-1, ORDINARY_MARK, PAUSE_TIME_SLOT));
        var result = rewriteIntervals(homeBucketId, List.of(workerId), id -> rules).get(workerId);
        return switch (result.status()) {
            case TRANSITIONED -> WorkerSchedulingChangeStatus.APPLIED;
            case NOOP -> WorkerSchedulingChangeStatus.UNCHANGED;
            case STALE -> WorkerSchedulingChangeStatus.MISSING;
            case INVALID -> WorkerSchedulingChangeStatus.CONFLICT;
        };
    }

    @Override
    public WorkerSchedulingChangeStatus resumeScheduling(String homeBucketId, String workerId) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        var state = readScoreStates(homeBucketId, List.of(workerId)).get(workerId);
        if (state == null) return WorkerSchedulingChangeStatus.MISSING;
        if (state.timeMillis() != PAUSE_TIME_MILLIS) return WorkerSchedulingChangeStatus.UNCHANGED;
        long nowMillis = currentTimeMillis.getAsLong();
        var result = releaseObservedScores(homeBucketId, Map.of(workerId, state.score()),
                nowMillis, false, nowMillis).get(workerId);
        return switch (result.status()) {
            case TRANSITIONED -> WorkerSchedulingChangeStatus.APPLIED;
            case NOOP -> WorkerSchedulingChangeStatus.UNCHANGED;
            case STALE, INVALID -> WorkerSchedulingChangeStatus.CONFLICT;
        };
    }

    private Map<String, WorkerScoreState> readScoreStates(String homeBucketId, List<String> workerIds) {
        List<Double> loaded = readScores(homeBucketId, workerIds);
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        for (int index = 0; index < workerIds.size(); index++) {
            Double rawScore = loaded.get(index);
            states.put(
                    workerIds.get(index),
                    rawScore == null
                            ? null
                            : decodeState(workerIds.get(index), rawScore)
            );
        }
        return states;
    }

    @Override
    public Map<String, Long> observeDueHotScoreCandidates(
            String workerGroupId,
            Long hotEligibilityFloorMillis,
            int limit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (limit < 1) {
            throw new IllegalArgumentException("candidate observation requires a positive limit");
        }
        long floorSlot;
        if (hotEligibilityFloorMillis == null) {
            floorSlot = MIN_BASE;
        } else if (!validTimeMillis(hotEligibilityFloorMillis)) {
            return Map.of();
        } else {
            floorSlot = Math.max(MIN_BASE, hotEligibilityFloorMillis / SLOT_MILLIS);
        }
        long lastDueSlot = currentSlot() - 1;
        return decodedHead(readAscendingRange(workerGroupId,
                score(1, ORDINARY_MARK, floorSlot), score(1, ORDINARY_MARK, lastDueSlot), limit));
    }

    @Override
    public Map<String, Long> observeHotCandidateScoresBefore(
            String group, Long floorMillis, long cutoffMillis, int limit) {
        requireNonBlank(group, "homeBucketId");
        if (limit < 1 || limit > MAX_SCORE_BATCH_SIZE) {
            throw new IllegalArgumentException("candidate observation requires limit 1..100");
        }
        if (!validTimeMillis(cutoffMillis) || (floorMillis != null && !validTimeMillis(floorMillis))) {
            return Map.of();
        }
        long floorSlot = floorMillis == null ? MIN_TIME_SLOT : floorMillis / SLOT_MILLIS;
        long lastSlot = Math.min(currentSlot(), cutoffMillis / SLOT_MILLIS) - 1;
        return decodedHead(readAscendingRange(group,
                score(1, CANDIDATE_MARK, floorSlot), score(1, CANDIDATE_MARK, lastSlot), limit));
    }

    private static Map<String, Long> decodedHead(List<ScoredValue<String>> rows) {
        var candidates = new LinkedHashMap<String, Long>();
        for (var row : rows) {
            try {
                var state = decodeState(row.getValue(), row.getScore());
                candidates.put(state.workerId(), state.score());
            } catch (IllegalStateException corrupt) {
                // Raw-row budgets include corruption. Never read replacements.
            }
        }
        return java.util.Collections.unmodifiableMap(candidates);
    }

    @Override
    public Map<String, Long> observeHotCandidatesBefore(String group, long cutoffMillis, int limit) {
        requireNonBlank(group, "homeBucketId");
        if (limit <= 0 || !validTimeMillis(cutoffMillis)) return Map.of();
        long lastSlot = cutoffMillis / SLOT_MILLIS - 1;
        if (lastSlot < 0) return Map.of();
        return mergedTimeRanges(group, 1, lastSlot, limit);
    }

    @Override
    public Map<String, Long> observeRecoveryRecheckCandidates(String group, int limit) {
        requireNonBlank(group, "homeBucketId");
        if (limit <= 0) return Map.of();
        long lastSlot = currentSlot() - 1;
        if (lastSlot <= COLD_PARK_TIME_SLOT) return Map.of();
        return mergedTimeRanges(group, -1, lastSlot, limit);
    }

    @Override
    public Set<String> initializeRegisteredScores(String homeBucketId, List<String> workerIds) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > MAX_REGISTRATION_BATCH_SIZE
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique IDs");
        }
        workerIds.forEach(id -> requireNonBlank(id, "workerId"));
        return initializeAbsent(homeBucketId, workerIds, score(-1, ORDINARY_MARK, COLD_PARK_TIME_SLOT));
    }

    @Override
    public List<String> sampleRegisteredWorkerIds(String homeBucketId, int limit) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (limit < 1 || limit > MAX_REGISTERED_WORKER_SAMPLE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and "
                    + MAX_REGISTERED_WORKER_SAMPLE_LIMIT);
        }
        return sampleMembers(homeBucketId, limit);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> candidateizeObservedHotScores(
            String group, Map<String, Long> observedScores) {
        return updateCandidateLane(group, observedScores, ORDINARY_MARK);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> recycleObservedHotCandidates(
            String group, Map<String, Long> observedScores) {
        return updateCandidateLane(group, observedScores, CANDIDATE_MARK);
    }

    private Map<String, WorkerScoreTransitionResult> updateCandidateLane(
            String group, Map<String, Long> observedScores, int sourceMark) {
        requireNonBlank(group, "homeBucketId");
        var ordered = workerValues(observedScores, "observedScores");
        long now = currentSlot();
        var immediate = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var targets = new LinkedHashMap<String, long[]>();
        ordered.forEach((id, observed) -> {
            WorkerScoreState state = decodeOrNull(id, observed);
            if (state == null || state.polarity() != WorkerScorePolarity.HOT_ACQUIRE
                    || state.mark() != sourceMark) {
                immediate.put(id, transition(WorkerScoreTransitionStatus.INVALID));
            } else if (timeSlot(state) >= now) {
                immediate.put(id, transition(WorkerScoreTransitionStatus.STALE));
            } else {
                // Candidateize keeps the generation time; recycling advances it to now.
                targets.put(id, new long[]{observed, sourceMark == ORDINARY_MARK
                        ? score(1, CANDIDATE_MARK, timeSlot(state))
                        : score(1, ORDINARY_MARK, now), 0});
            }
        });
        // Candidate lanes keep one Lua for the complete caller-bounded batch.
        return mergeOrderedResults(ordered.keySet(), immediate,
                exactCas(group, targets, false, Integer.MAX_VALUE));
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            acquireObservedHotScoreLeases(
                    String homeBucketId,
                    Map<String, Long> observedScores,
                    long targetTimeMillis
            ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (observedScores == null) {
            throw new IllegalArgumentException(
                    "observedScores must be present"
            );
        }
        if (observedScores.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
        observedScores.forEach((workerId, observedScore) -> {
            requireNonBlank(workerId, "workerId");
            if (observedScore == null) {
                throw new IllegalArgumentException(
                        "observedScore must be present"
                );
            }
            ordered.put(workerId, observedScore);
        });
        long now = currentSlot();
        if (!validTimeMillis(targetTimeMillis) || targetTimeMillis / SLOT_MILLIS <= now) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }
        long leaseScore = score(1, ORDINARY_MARK, targetTimeMillis / SLOT_MILLIS);
        var immediate = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var targets = new LinkedHashMap<String, long[]>();
        ordered.forEach((workerId, observed) -> {
            WorkerScoreState state = decodeOrNull(workerId, observed);
            if (state == null || state.polarity() != WorkerScorePolarity.HOT_ACQUIRE) {
                immediate.put(workerId, transition(WorkerScoreTransitionStatus.INVALID));
            } else if (timeSlot(state) >= now) {
                immediate.put(workerId, transition(WorkerScoreTransitionStatus.STALE));
            } else {
                targets.put(workerId, new long[]{observed, leaseScore, 0});
            }
        });
        return mergeOrderedResults(ordered.keySet(), immediate, exactCas(homeBucketId, targets, false));
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> acquireCurrentHotScoreLeases(
            String homeBucketId,
            List<String> workerIds,
            long targetTimeMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null) {
            throw new IllegalArgumentException("workerIds must be present");
        }
        List<String> ordered = new ArrayList<>(workerIds);
        ordered.forEach(id -> requireNonBlank(id, "workerId"));
        if (new LinkedHashSet<>(ordered).size() != ordered.size()) {
            throw new IllegalArgumentException("workerIds must be unique");
        }
        if (ordered.isEmpty()) {
            return Map.of();
        }
        long now = currentSlot();
        if (!validTimeMillis(targetTimeMillis) || targetTimeMillis / SLOT_MILLIS <= now) {
            return uniformResults(ordered, WorkerScoreTransitionStatus.INVALID);
        }
        long leaseScore = score(1, ORDINARY_MARK, targetTimeMillis / SLOT_MILLIS);
        var rules = new IntervalRules();
        for (int mark : new int[]{ORDINARY_MARK, CANDIDATE_MARK}) {
            rules.addSlots(1, mark, 0, now - 1, WRITE, leaseScore)
                    .addSlots(1, mark, now, MAX_TIME_SLOT, STALE, 0);
        }
        rules.add(-MAX_ABSOLUTE_SCORE, -1, STALE, 0);
        return rewriteIntervals(homeBucketId, ordered, id -> rules);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> advancePastScoreTimesToNow(
            String homeBucketId,
            List<String> workerIds
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (workerIds == null || workerIds.isEmpty() || workerIds.size() > 100
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique IDs");
        }
        workerIds.forEach(id -> requireNonBlank(id, "workerId"));
        long now = currentSlot();
        // Past HOT clears the candidate mark; past non-cold RECOVERY keeps its mark.
        var rules = new IntervalRules()
                .addSlots(1, ORDINARY_MARK, 0, now - 1, WRITE, score(1, ORDINARY_MARK, now))
                .addSlots(1, CANDIDATE_MARK, 0, now - 1, WRITE, score(1, ORDINARY_MARK, now))
                .addSlots(-1, ORDINARY_MARK, COLD_PARK_TIME_SLOT + 1, now - 1,
                        WRITE, score(-1, ORDINARY_MARK, now))
                .addSlots(-1, CANDIDATE_MARK, COLD_PARK_TIME_SLOT + 1, now - 1,
                        WRITE, score(-1, CANDIDATE_MARK, now))
                .add(1, MAX_ABSOLUTE_SCORE, NOOP, 0)
                .add(-MAX_ABSOLUTE_SCORE, -1, NOOP, 0);
        return rewriteIntervals(homeBucketId, workerIds, id -> rules);
    }

    @Override
    public WorkerScoreTransitionResult toggleCurrentPolarity(
            String homeBucketId,
            String workerId,
            long observedScore
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        WorkerScoreState observed = decodeOrNull(workerId, observedScore);
        if (observed == null || timeSlot(observed) == MIN_TIME_SLOT) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        int flipped = -polarityValue(observed.polarity());
        long target = score(flipped, ORDINARY_MARK, timeSlot(observed));
        return exactCas(homeBucketId, singleTarget(workerId, observedScore, target), false).get(workerId);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> deferObservedToRecovery(
            String homeBucketId, Map<String, Long> observedScores, long delayMillis
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                observedScores,
                "observedScores"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }
        if (delayMillis <= 0 || delayMillis >= MAX_TIME_MILLIS) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }
        long nowMillis = currentTimeMillis.getAsLong();
        long now = nowMillis / SLOT_MILLIS;
        long targetSlot = (nowMillis + delayMillis) / SLOT_MILLIS;
        boolean validTarget = validTimeMillis(nowMillis) && targetSlot > MIN_TIME_SLOT
                && targetSlot <= MAX_TIME_SLOT;
        var immediate = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var targets = new LinkedHashMap<String, long[]>();
        ordered.forEach((workerId, observed) -> {
            WorkerScoreState state = decodeOrNull(workerId, observed);
            if (state == null || !validTarget || (state.polarity() == WorkerScorePolarity.RECOVERY_RECHECK
                    && timeSlot(state) <= COLD_PARK_TIME_SLOT)) {
                immediate.put(workerId, transition(WorkerScoreTransitionStatus.INVALID));
            } else if (timeSlot(state) >= now) {
                immediate.put(workerId, transition(WorkerScoreTransitionStatus.STALE));
            } else {
                targets.put(workerId, new long[]{observed, score(-1, ORDINARY_MARK, targetSlot), 0});
            }
        });
        return mergeOrderedResults(ordered.keySet(), immediate, exactCas(homeBucketId, targets, false));
    }

    @Override
    public Map<String, WorkerScoreTransitionResult>
            rewriteCurrentPolarityWithinTimeFence(
                    String homeBucketId,
                    Map<String, Long> suppliedTimeMillisByWorkerId,
                    WorkerScorePolarity targetPolarity,
                    long minimumTimeMillis
            ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        if (targetPolarity == null) {
            throw new IllegalArgumentException(
                    "targetPolarity must be present"
            );
        }
        LinkedHashMap<String, Long> ordered = boundedWorkerValues(
                suppliedTimeMillisByWorkerId,
                "suppliedTimeMillisByWorkerId"
        );
        if (ordered.isEmpty()) {
            return Map.of();
        }
        long nowMillis = currentTimeMillis.getAsLong();
        LinkedHashMap<String, WorkerScoreTransitionResult> immediate = new LinkedHashMap<>();
        LinkedHashMap<String, Long> pending = new LinkedHashMap<>();
        ordered.forEach((workerId, suppliedTimeMillis) -> {
            if (!validTimeMillis(nowMillis) || !validTimeMillis(minimumTimeMillis)
                    || suppliedTimeMillis <= 0 || !validTimeMillis(suppliedTimeMillis)) {
                immediate.put(workerId, transition(WorkerScoreTransitionStatus.INVALID));
            } else {
                pending.put(workerId, suppliedTimeMillis / SLOT_MILLIS);
            }
        });
        if (pending.isEmpty()) {
            return mergeOrderedResults(ordered.keySet(), immediate, Map.of());
        }
        int sign = polarityValue(targetPolarity);
        long now = nowMillis / SLOT_MILLIS;
        long minimum = minimumTimeMillis / SLOT_MILLIS;
        var bySuppliedSlot = new LinkedHashMap<Long, IntervalRules>();
        var changed = rewriteIntervals(homeBucketId, List.copyOf(pending.keySet()),
                id -> bySuppliedSlot.computeIfAbsent(pending.get(id),
                        supplied -> polarityRules(sign, supplied, now, minimum)));
        return mergeOrderedResults(ordered.keySet(), immediate, changed);
    }

    /**
     * Rules for one supplied evidence slot E, owner now N and activation minimum F.
     * Current/future coordinates keep time and mark; admitted past changes write
     * max(T + 1, min(E, N)) with mark=0, which is constant within each emitted interval.
     */
    private static IntervalRules polarityRules(int sign, long supplied, long now, long minimum) {
        var rules = new IntervalRules();
        long nearest = Math.min(supplied, now);
        long lastAdmitted = Math.min(supplied, now - 1);
        boolean activationBlocked = supplied < minimum || now < minimum;
        for (int polarity : new int[]{1, -1}) {
            for (int mark : new int[]{ORDINARY_MARK, CANDIDATE_MARK}) {
                long first = mark == ORDINARY_MARK ? MIN_BASE : MIN_TIME_SLOT;
                rules.addSlots(polarity, mark, now, MAX_TIME_SLOT, SIGN, sign);
                rules.addSlots(polarity, mark, lastAdmitted + 1, now - 1, STALE, 0);
                // Below the activation minimum every past change needs post-minimum evidence and time.
                long lastBelowMinimum = Math.min(lastAdmitted, minimum - 1);
                if (activationBlocked) {
                    rules.addSlots(polarity, mark, first, lastBelowMinimum, STALE, 0);
                } else {
                    rules.addPastPolarityWrites(polarity, mark, first, lastBelowMinimum, sign, nearest);
                }
                long firstAtMinimum = Math.max(first, minimum);
                if (polarity == sign) {
                    rules.addSlots(polarity, mark, firstAtMinimum, lastAdmitted, NOOP, 0);
                } else {
                    rules.addPastPolarityWrites(polarity, mark, firstAtMinimum, lastAdmitted, sign, nearest);
                }
            }
        }
        return rules;
    }

    @Override
    public WorkerScoreTransitionResult parkObservedRecoveryScore(
            String homeBucketId,
            String workerId,
            long observedScore
    ) {
        requireNonBlank(homeBucketId, "homeBucketId");
        requireNonBlank(workerId, "workerId");
        WorkerScoreState observed = decodeOrNull(workerId, observedScore);
        if (observed == null || observed.polarity() != WorkerScorePolarity.RECOVERY_RECHECK) {
            return transition(WorkerScoreTransitionStatus.INVALID);
        }
        long target = score(-1, observed.mark(), COLD_PARK_TIME_SLOT);
        return exactCas(homeBucketId, singleTarget(workerId, observedScore, target), false).get(workerId);
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> releaseScoreHolds(
            String homeBucketId, Map<String, Long> observedScores, long releaseTimeMillis
    ) {
        return releaseObservedScores(homeBucketId, observedScores, releaseTimeMillis, false,
                currentTimeMillis.getAsLong());
    }

    @Override
    public Map<String, WorkerScoreTransitionResult> releaseObservedHotScoreHolds(
            String homeBucketId, Map<String, Long> observedHotScores, long releaseTimeMillis
    ) {
        return releaseObservedScores(homeBucketId, observedHotScores, releaseTimeMillis, true,
                currentTimeMillis.getAsLong());
    }

    private Map<String, WorkerScoreTransitionResult> releaseObservedScores(
            String group, Map<String, Long> observations, long releaseTimeMillis, boolean acceptCounterpart,
            long nowMillis
    ) {
        requireNonBlank(group, "homeBucketId");
        if (observations == null) {
            throw new IllegalArgumentException(acceptCounterpart
                    ? "observedHotScores must be present" : "observedScores must be present");
        }
        if (observations.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> ordered = new LinkedHashMap<>();
        observations.forEach((id, score) -> {
            requireNonBlank(id, "workerId");
            if (score == null) {
                throw new IllegalArgumentException(acceptCounterpart
                        ? "observedHotScore must be present" : "observedScore must be present");
            }
            ordered.put(id, score);
        });
        if (!validTimeMillis(releaseTimeMillis) || !validTimeMillis(nowMillis)) {
            return uniformResults(ordered.keySet(), WorkerScoreTransitionStatus.INVALID);
        }
        // Callers sample their time before this Owner does. A slot that has already passed is raised
        // to the current one, so a release never writes before the current slot or recreates an older fence.
        long releaseSlot = Math.max(releaseTimeMillis, nowMillis) / SLOT_MILLIS;
        LinkedHashMap<String, WorkerScoreTransitionResult> immediate = new LinkedHashMap<>();
        LinkedHashMap<String, long[]> targets = new LinkedHashMap<>();
        ordered.forEach((id, observed) -> {
            WorkerScoreState state = decodeOrNull(id, observed);
            boolean valid = state != null && (!acceptCounterpart
                    || state.polarity() == WorkerScorePolarity.HOT_ACQUIRE);
            if (!valid || releaseSlot > timeSlot(state)
                    || (releaseSlot == timeSlot(state) && state.mark() == ORDINARY_MARK)) {
                immediate.put(id, transition(WorkerScoreTransitionStatus.INVALID));
            } else {
                targets.put(id, new long[]{observed, replaceTime(observed, releaseSlot), 0});
            }
        });
        Map<String, WorkerScoreTransitionResult> changed = exactCas(group, targets, acceptCounterpart);
        if (acceptCounterpart && !changed.isEmpty()) {
            changed.replaceAll((id, result) -> result.status() == WorkerScoreTransitionStatus.NOOP
                    ? new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, result.score())
                    : result);
        }
        return mergeOrderedResults(ordered.keySet(), immediate, changed);
    }

    // Fixed Redis operations; public entry points above only prepare and combine inputs/results.
    private List<Double> readScores(String group, List<String> workerIds) {
        return commands().zmscore(scoreKey(group), workerIds.toArray(String[]::new));
    }

    private List<ScoredValue<String>> readAscendingRange(String group, long minimum, long maximum, int limit) {
        if (maximum < minimum) return List.of();
        return commands().zrangebyscoreWithScores(scoreKey(group),
                Range.create(minimum, maximum), Limit.create(0, limit));
    }

    private Set<String> initializeAbsent(String group, List<String> workerIds, long initialScore) {
        List<String> arguments = new ArrayList<>(workerIds.size() + 1);
        arguments.add(Long.toString(initialScore));
        arguments.addAll(workerIds);
        List<String> created = commands().eval(INITIALIZE_REGISTERED_SCRIPT, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, arguments.toArray(String[]::new));
        return new LinkedHashSet<>(created);
    }

    private List<String> sampleMembers(String group, int limit) {
        return commands().zrandmember(scoreKey(group), limit);
    }

    /** Each value holds the exact observation and the complete Java target; 100 members per Lua. */
    private Map<String, WorkerScoreTransitionResult> exactCas(
            String group, Map<String, long[]> targets, boolean acceptCounterpart
    ) {
        return exactCas(group, targets, acceptCounterpart, MAX_SCORE_BATCH_SIZE);
    }

    private Map<String, WorkerScoreTransitionResult> exactCas(
            String group, Map<String, long[]> targets, boolean acceptCounterpart, int membersPerLua
    ) {
        if (targets.isEmpty()) {
            return Map.of();
        }
        var results = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        List<String> ids = new ArrayList<>(targets.keySet());
        for (int offset = 0; offset < ids.size(); offset += membersPerLua) {
            List<String> batch = ids.subList(offset, Math.min(offset + membersPerLua, ids.size()));
            List<String> arguments = new ArrayList<>(batch.size() * 4);
            for (String id : batch) {
                long[] pair = targets.get(id);
                arguments.add(id);
                arguments.add(Long.toString(pair[0]));
                arguments.add(Long.toString(pair[1]));
                arguments.add(acceptCounterpart ? Long.toString(-pair[0]) : "");
            }
            results.putAll(executeBatch(group, batch, EXACT_CAS_SCRIPT, arguments, "exact_cas"));
        }
        return results;
    }

    /** Members sharing a rule table send it once; 100 members per Lua. */
    private Map<String, WorkerScoreTransitionResult> rewriteIntervals(
            String group, List<String> workerIds, java.util.function.Function<String, IntervalRules> rulesFor
    ) {
        var results = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        for (int offset = 0; offset < workerIds.size(); offset += MAX_SCORE_BATCH_SIZE) {
            List<String> batch = workerIds.subList(offset,
                    Math.min(offset + MAX_SCORE_BATCH_SIZE, workerIds.size()));
            var tables = new LinkedHashMap<IntervalRules, Integer>();
            var members = new ArrayList<String>(batch.size() * 2);
            for (String id : batch) {
                int index = tables.computeIfAbsent(rulesFor.apply(id), table -> tables.size() + 1);
                members.add(id);
                members.add(Integer.toString(index));
            }
            var arguments = new ArrayList<String>();
            arguments.add(Integer.toString(tables.size()));
            tables.keySet().forEach(table -> table.appendTo(arguments));
            arguments.addAll(members);
            results.putAll(executeBatch(group, batch, INTERVAL_REWRITE_SCRIPT, arguments, "interval_rewrite"));
        }
        return results;
    }

    private Map<String, WorkerScoreTransitionResult> executeBatch(
            String group, Iterable<String> ids, String script, List<String> arguments, String operation
    ) {
        return batchScriptResults(ids, commands().eval(script, ScriptOutputType.MULTI,
                new String[]{scoreKey(group)}, arguments.toArray(String[]::new)), operation);
    }

    private static Map<String, WorkerScoreTransitionResult>
            batchScriptResults(
                    Iterable<String> workerIds,
                    Object raw,
                    String operation
            ) {
        List<String> expectedIds = new ArrayList<>();
        workerIds.forEach(expectedIds::add);
        if (!(raw instanceof List<?> values)
                || values.size() != expectedIds.size() * 3) {
            throw new IllegalStateException(
                    operation + " batch result is invalid"
            );
        }
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index += 3) {
            String workerId = String.valueOf(values.get(index));
            WorkerScoreTransitionResult result = scriptResult(
                    values.get(index + 1),
                    values.get(index + 2)
            );
            if (results.put(workerId, result) != null) {
                throw new IllegalStateException(
                        operation + " batch contains duplicate ids"
                );
            }
        }
        if (!List.copyOf(results.keySet()).equals(expectedIds)) {
            throw new IllegalStateException(
                    operation + " batch identities are invalid"
            );
        }
        return results;
    }

    private static Map<String, WorkerScoreTransitionResult>
            mergeOrderedResults(
                    Iterable<String> workerIds,
                    Map<String, WorkerScoreTransitionResult> immediate,
                    Map<String, WorkerScoreTransitionResult> transitioned
            ) {
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        workerIds.forEach(workerId -> results.put(
                workerId,
                immediate.containsKey(workerId)
                        ? immediate.get(workerId)
                        : transitioned.get(workerId)
        ));
        return results;
    }

    private static <T> LinkedHashMap<String, T> boundedWorkerValues(
            Map<String, T> values,
            String name
    ) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        if (values.size() > MAX_SCORE_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most "
                            + MAX_SCORE_BATCH_SIZE + " workers"
            );
        }
        return workerValues(values, name);
    }

    private static <T> LinkedHashMap<String, T> workerValues(Map<String, T> values, String name) {
        if (values == null) throw new IllegalArgumentException(name + " must be present");
        LinkedHashMap<String, T> ordered = new LinkedHashMap<>();
        values.forEach((workerId, value) -> {
            requireNonBlank(workerId, "workerId");
            if (value == null) {
                throw new IllegalArgumentException(
                        name + " must not contain null values"
                );
            }
            ordered.put(workerId, value);
        });
        return ordered;
    }

    private static Map<String, long[]> singleTarget(String workerId, long observed, long target) {
        var targets = new LinkedHashMap<String, long[]>();
        targets.put(workerId, new long[]{observed, target, 0});
        return targets;
    }

    private static WorkerScoreState decodeOrNull(String workerId, long score) {
        try {
            return decodeState(workerId, (double) score);
        } catch (IllegalStateException invalid) {
            return null;
        }
    }

    private static Map<String, WorkerScoreTransitionResult> uniformResults(
            Iterable<String> workerIds,
            WorkerScoreTransitionStatus status
    ) {
        LinkedHashMap<String, WorkerScoreTransitionResult> results =
                new LinkedHashMap<>();
        workerIds.forEach(workerId -> results.put(
                workerId,
                transition(status)
        ));
        return results;
    }

    private static WorkerScoreTransitionResult transition(
            WorkerScoreTransitionStatus status
    ) {
        return new WorkerScoreTransitionResult(status, null);
    }

    private Map<String, Long> mergedTimeRanges(String group, int polarity, long lastSlot, int limit) {
        var rows = new ArrayList<ScoredValue<String>>();
        for (int mark = 0; mark <= 1; mark++) {
            long first = polarity < 0 ? COLD_PARK_TIME_SLOT + 1 : (mark == 0 ? MIN_BASE : 0);
            if (lastSlot < first) continue;
            long a = score(polarity, mark, first);
            long b = score(polarity, mark, lastSlot);
            rows.addAll(readDescendingRange(group, Math.min(a, b), Math.max(a, b), limit));
        }
        // Preserve logical time, old mark tie order and Redis's reverse bytewise member order.
        // Merge raw rows before decoding: selected corruption consumes budget and may throw.
        rows.sort((left, right) -> {
            double a = Math.abs(left.getScore()), b = Math.abs(right.getScore());
            int time = Double.compare(a % MARK_BASE, b % MARK_BASE) * -polarity;
            if (time != 0) return time;
            int mark = Double.compare(Math.floor(a / MARK_BASE), Math.floor(b / MARK_BASE)) * -polarity;
            if (mark != 0) return mark;
            return java.util.Arrays.compareUnsigned(right.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    left.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        var observations = new LinkedHashMap<String, Long>();
        for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
            observations.put(row.getValue(), scoreToLong(row.getScore()));
        }
        return java.util.Collections.unmodifiableMap(observations);
    }

    private List<ScoredValue<String>> readDescendingRange(String group, long minimum, long maximum, int limit) {
        return commands().zrevrangebyscoreWithScores(scoreKey(group), maximum, minimum, 0, limit);
    }

    /** A non-integer echo is reported as INVALID without a score; it never fails the batch. */
    private static WorkerScoreTransitionResult scriptResult(Object rawStatus, Object rawScore) {
        WorkerScoreTransitionStatus status = switch (String.valueOf(rawStatus)) {
            case "transitioned" -> WorkerScoreTransitionStatus.TRANSITIONED;
            case "noop" -> WorkerScoreTransitionStatus.NOOP;
            case "stale" -> WorkerScoreTransitionStatus.STALE;
            case "invalid" -> WorkerScoreTransitionStatus.INVALID;
            default -> throw new IllegalStateException(
                    "Worker score script status is invalid"
            );
        };
        String echo = rawScore == null ? "" : String.valueOf(rawScore);
        if (echo.isEmpty()) {
            return new WorkerScoreTransitionResult(status, null);
        }
        try {
            return new WorkerScoreTransitionResult(status, new BigDecimal(echo).longValueExact());
        } catch (NumberFormatException | ArithmeticException corrupt) {
            return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID, null);
        }
    }

    private long currentSlot() {
        return currentTimeMillis.getAsLong() / SLOT_MILLIS;
    }

    private String scoreKey(String homeBucketId) {
        return keyspace.base() + ":worker:score:" + homeBucketId;
    }

    private RedisCommands<String, String> commands() {
        return connection().sync();
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = connection;
                if (current == null || !current.isOpen()) {
                    current = redisClient.connect(StringCodec.UTF8);
                    connection = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    /** One ordered interval table, complete before use; equal tables are sent once per batch. */
    private static final class IntervalRules {
        private final List<String> values = new ArrayList<>();

        IntervalRules add(long minimum, long maximum, String action, long value) {
            if (minimum <= maximum) {
                values.addAll(List.of(Long.toString(minimum), Long.toString(maximum), action, Long.toString(value)));
            }
            return this;
        }

        /** Slots [first, last] of one polarity/mark lane, clipped to the legal coordinate range. */
        IntervalRules addSlots(int polarity, int mark, long first, long last, String action, long value) {
            long from = Math.max(first, mark == ORDINARY_MARK ? MIN_BASE : MIN_TIME_SLOT);
            long to = Math.min(last, MAX_TIME_SLOT);
            if (from > to) return this;
            long a = score(polarity, mark, from);
            long b = score(polarity, mark, to);
            return add(Math.min(a, b), Math.max(a, b), action, value);
        }

        /** Past slots [first, last] write max(slot + 1, nearest) as mark=0 in the target polarity. */
        void addPastPolarityWrites(int polarity, int mark, long first, long last, int sign, long nearest) {
            addSlots(polarity, mark, first, Math.min(last, nearest - 1), WRITE, score(sign, ORDINARY_MARK, nearest));
            if (nearest >= first && nearest <= last) {
                addSlots(polarity, mark, nearest, nearest, WRITE, score(sign, ORDINARY_MARK, nearest + 1));
            }
        }

        void appendTo(List<String> arguments) {
            arguments.add(Integer.toString(values.size() / 4));
            arguments.addAll(values);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IntervalRules rules && rules.values.equals(values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }
    }
}
