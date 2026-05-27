package com.xa.mass.engine.strategy;

/**
 * Concrete rank policy for the current default worker candidate ranker.
 *
 * <p>This is a small value object, not a plugin registry. Candidate source,
 * rule eligibility, reserve, and dispatch remain separate owners.</p>
 */
public record WorkerCandidateRankPolicy(double loadWeight,
                                        double affinityWeight,
                                        double availabilityWeight) {

    public static final double DEFAULT_LOAD_WEIGHT = 0.6d;
    public static final double DEFAULT_AFFINITY_WEIGHT = 0.3d;
    public static final double DEFAULT_AVAILABILITY_WEIGHT = 0.1d;

    public WorkerCandidateRankPolicy {
        loadWeight = nonNegative(loadWeight);
        affinityWeight = nonNegative(affinityWeight);
        availabilityWeight = nonNegative(availabilityWeight);
    }

    public static WorkerCandidateRankPolicy defaultPolicy() {
        return new WorkerCandidateRankPolicy(
                DEFAULT_LOAD_WEIGHT,
                DEFAULT_AFFINITY_WEIGHT,
                DEFAULT_AVAILABILITY_WEIGHT
        );
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 0.0d;
    }
}
