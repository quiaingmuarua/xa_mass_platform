package com.xa.mass.workerpack.sample.command.fixture;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Sample-worker fault profile value object.
 *
 * <p>This is worker-pack/test-harness state only. It describes how a sample
 * worker may behave when a later command surface applies the profile; it does
 * not mutate engine, transport, or runtime owner state directly.</p>
 */
public final class SampleWorkerFaultProfile {

    public enum ProfileName {
        FAST,
        NORMAL,
        SLOW,
        NEAR_TIMEOUT,
        STUCK,
        FLAKY_RESULT,
        FLAKY_TRANSPORT,
        MALFORMED_RESULT,
        WRONG_IDENTITY,
        NOISY;

        static ProfileName fromValue(String value) {
            if (value == null || value.isBlank()) {
                return FAST;
            }
            try {
                return ProfileName.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unsupported fault profile: " + value);
            }
        }
    }

    public enum DelayDistribution {
        NONE,
        FIXED,
        UNIFORM
    }

    public enum ResultDropMode {
        OFF,
        ONCE,
        ALWAYS,
        PERCENT
    }

    public enum StallMode {
        OFF,
        DURATION,
        LEASE_EXPIRY,
        FOREVER
    }

    public enum MalformedResultKind {
        NONE,
        MISSING_MESSAGE_ID,
        INVALID_STATUS,
        INVALID_PAYLOAD
    }

    public enum ResultIdentityKind {
        NONE,
        WRONG_TASK,
        WRONG_MESSAGE,
        WRONG_WORKER,
        WRONG_LEASE
    }

    public enum DisconnectPhase {
        NONE,
        BEFORE_RECEIVE,
        AFTER_RECEIVE,
        BEFORE_RESULT,
        AFTER_RESULT
    }

    private final boolean enabled;
    private final ProfileName profileName;
    private final long seed;
    private final long minDelayMillis;
    private final long maxDelayMillis;
    private final DelayDistribution delayDistribution;
    private final ResultDropMode resultDropMode;
    private final int resultDropPercent;
    private final int duplicateResultCount;
    private final long duplicateResultGapMillis;
    private final long lateResultDelayMillis;
    private final StallMode stallMode;
    private final long stallMillis;
    private final MalformedResultKind malformedResultKind;
    private final ResultIdentityKind resultIdentityKind;
    private final DisconnectPhase disconnectPhase;

    private SampleWorkerFaultProfile(Builder builder) {
        this.enabled = builder.enabled;
        this.profileName = builder.profileName == null ? ProfileName.FAST : builder.profileName;
        this.seed = builder.seed;
        this.minDelayMillis = Math.max(0L, builder.minDelayMillis);
        this.maxDelayMillis = Math.max(this.minDelayMillis, builder.maxDelayMillis);
        this.delayDistribution = builder.delayDistribution == null ? DelayDistribution.NONE : builder.delayDistribution;
        this.resultDropMode = builder.resultDropMode == null ? ResultDropMode.OFF : builder.resultDropMode;
        this.resultDropPercent = Math.max(0, Math.min(100, builder.resultDropPercent));
        this.duplicateResultCount = Math.max(0, builder.duplicateResultCount);
        this.duplicateResultGapMillis = Math.max(0L, builder.duplicateResultGapMillis);
        this.lateResultDelayMillis = Math.max(0L, builder.lateResultDelayMillis);
        this.stallMode = builder.stallMode == null ? StallMode.OFF : builder.stallMode;
        this.stallMillis = Math.max(0L, builder.stallMillis);
        this.malformedResultKind = builder.malformedResultKind == null
                ? MalformedResultKind.NONE
                : builder.malformedResultKind;
        this.resultIdentityKind = builder.resultIdentityKind == null
                ? ResultIdentityKind.NONE
                : builder.resultIdentityKind;
        this.disconnectPhase = builder.disconnectPhase == null ? DisconnectPhase.NONE : builder.disconnectPhase;
    }

    public static SampleWorkerFaultProfile disabled() {
        return builder(ProfileName.FAST, 0L).enabled(false).build();
    }

    public static SampleWorkerFaultProfile fromProfile(String profile, long seed) {
        return fromProfile(ProfileName.fromValue(profile), seed);
    }

    public static SampleWorkerFaultProfile fromProfile(ProfileName profileName, long seed) {
        ProfileName profile = profileName == null ? ProfileName.FAST : profileName;
        Builder builder = builder(profile, seed).enabled(true);
        return switch (profile) {
            case FAST -> builder.build();
            case NORMAL -> builder.delay(10L, 60L, DelayDistribution.UNIFORM).build();
            case SLOW -> builder.delay(250L, 1_500L, DelayDistribution.UNIFORM).build();
            case NEAR_TIMEOUT -> builder.stallMode(StallMode.LEASE_EXPIRY).build();
            case STUCK -> builder.stallMode(StallMode.FOREVER).build();
            case FLAKY_RESULT -> builder
                    .resultDrop(ResultDropMode.PERCENT, 20)
                    .duplicateResult(1, 25L)
                    .build();
            case FLAKY_TRANSPORT -> builder.disconnectPhase(DisconnectPhase.BEFORE_RESULT).build();
            case MALFORMED_RESULT -> builder.malformedResultKind(MalformedResultKind.MISSING_MESSAGE_ID).build();
            case WRONG_IDENTITY -> builder.resultIdentityKind(ResultIdentityKind.WRONG_MESSAGE).build();
            case NOISY -> builder
                    .delay(20L, 250L, DelayDistribution.UNIFORM)
                    .resultDrop(ResultDropMode.PERCENT, 10)
                    .duplicateResult(1, 20L)
                    .build();
        };
    }

    public static Builder builder(ProfileName profileName, long seed) {
        return new Builder(profileName, seed);
    }

    public boolean enabled() {
        return enabled;
    }

    public ProfileName profileName() {
        return profileName;
    }

    public long seed() {
        return seed;
    }

    public long minDelayMillis() {
        return minDelayMillis;
    }

    public long maxDelayMillis() {
        return maxDelayMillis;
    }

    public DelayDistribution delayDistribution() {
        return delayDistribution;
    }

    public ResultDropMode resultDropMode() {
        return resultDropMode;
    }

    public int resultDropPercent() {
        return resultDropPercent;
    }

    public int duplicateResultCount() {
        return duplicateResultCount;
    }

    public long duplicateResultGapMillis() {
        return duplicateResultGapMillis;
    }

    public long lateResultDelayMillis() {
        return lateResultDelayMillis;
    }

    public StallMode stallMode() {
        return stallMode;
    }

    public long stallMillis() {
        return stallMillis;
    }

    public MalformedResultKind malformedResultKind() {
        return malformedResultKind;
    }

    public ResultIdentityKind resultIdentityKind() {
        return resultIdentityKind;
    }

    public DisconnectPhase disconnectPhase() {
        return disconnectPhase;
    }

    public long resolveDelayMillis(String workerId, String taskId, String messageId, int attempt) {
        if (!enabled || delayDistribution == DelayDistribution.NONE || maxDelayMillis <= 0L) {
            return 0L;
        }
        if (delayDistribution == DelayDistribution.FIXED || minDelayMillis == maxDelayMillis) {
            return minDelayMillis;
        }
        long range = maxDelayMillis - minDelayMillis + 1L;
        return minDelayMillis + stablePositiveHash(workerId, taskId, messageId, attempt, "delay") % range;
    }

    public boolean shouldDropResult(String workerId, String taskId, String messageId, int attempt) {
        if (!enabled) {
            return false;
        }
        return switch (resultDropMode) {
            case OFF -> false;
            case ONCE, ALWAYS -> true;
            case PERCENT -> stablePositiveHash(workerId, taskId, messageId, attempt, "drop") % 100 < resultDropPercent;
        };
    }

    public boolean shouldStallWithoutResult() {
        return enabled && (stallMode == StallMode.FOREVER || stallMode == StallMode.LEASE_EXPIRY);
    }

    public long resolveStallDelayMillis() {
        if (!enabled || stallMode != StallMode.DURATION) {
            return 0L;
        }
        return stallMillis;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("profile", profileName.name());
        map.put("seed", seed);
        map.put("delayDistribution", delayDistribution.name());
        map.put("minDelayMillis", minDelayMillis);
        map.put("maxDelayMillis", maxDelayMillis);
        map.put("resultDropMode", resultDropMode.name());
        map.put("resultDropPercent", resultDropPercent);
        map.put("duplicateResultCount", duplicateResultCount);
        map.put("duplicateResultGapMillis", duplicateResultGapMillis);
        map.put("lateResultDelayMillis", lateResultDelayMillis);
        map.put("stallMode", stallMode.name());
        map.put("stallMillis", stallMillis);
        map.put("malformedResultKind", malformedResultKind.name());
        map.put("resultIdentityKind", resultIdentityKind.name());
        map.put("disconnectPhase", disconnectPhase.name());
        return map;
    }

    private long stablePositiveHash(String workerId, String taskId, String messageId, int attempt, String salt) {
        return Integer.toUnsignedLong(Objects.hash(seed, workerId, taskId, messageId, attempt, salt));
    }

    public static final class Builder {
        private boolean enabled = true;
        private final ProfileName profileName;
        private final long seed;
        private long minDelayMillis;
        private long maxDelayMillis;
        private DelayDistribution delayDistribution = DelayDistribution.NONE;
        private ResultDropMode resultDropMode = ResultDropMode.OFF;
        private int resultDropPercent;
        private int duplicateResultCount;
        private long duplicateResultGapMillis;
        private long lateResultDelayMillis;
        private StallMode stallMode = StallMode.OFF;
        private long stallMillis;
        private MalformedResultKind malformedResultKind = MalformedResultKind.NONE;
        private ResultIdentityKind resultIdentityKind = ResultIdentityKind.NONE;
        private DisconnectPhase disconnectPhase = DisconnectPhase.NONE;

        private Builder(ProfileName profileName, long seed) {
            this.profileName = profileName == null ? ProfileName.FAST : profileName;
            this.seed = seed;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder delay(long minDelayMillis, long maxDelayMillis, DelayDistribution delayDistribution) {
            this.minDelayMillis = minDelayMillis;
            this.maxDelayMillis = maxDelayMillis;
            this.delayDistribution = delayDistribution;
            return this;
        }

        public Builder resultDrop(ResultDropMode resultDropMode, int resultDropPercent) {
            this.resultDropMode = resultDropMode;
            this.resultDropPercent = resultDropPercent;
            return this;
        }

        public Builder duplicateResult(int duplicateResultCount, long duplicateResultGapMillis) {
            this.duplicateResultCount = duplicateResultCount;
            this.duplicateResultGapMillis = duplicateResultGapMillis;
            return this;
        }

        public Builder lateResultDelay(long lateResultDelayMillis) {
            this.lateResultDelayMillis = lateResultDelayMillis;
            return this;
        }

        public Builder stallMode(StallMode stallMode) {
            this.stallMode = stallMode;
            this.stallMillis = 0L;
            return this;
        }

        public Builder stallDuration(long stallMillis) {
            this.stallMode = StallMode.DURATION;
            this.stallMillis = stallMillis;
            return this;
        }

        public Builder malformedResultKind(MalformedResultKind malformedResultKind) {
            this.malformedResultKind = malformedResultKind;
            return this;
        }

        public Builder resultIdentityKind(ResultIdentityKind resultIdentityKind) {
            this.resultIdentityKind = resultIdentityKind;
            return this;
        }

        public Builder disconnectPhase(DisconnectPhase disconnectPhase) {
            this.disconnectPhase = disconnectPhase;
            return this;
        }

        public SampleWorkerFaultProfile build() {
            return new SampleWorkerFaultProfile(this);
        }
    }
}
