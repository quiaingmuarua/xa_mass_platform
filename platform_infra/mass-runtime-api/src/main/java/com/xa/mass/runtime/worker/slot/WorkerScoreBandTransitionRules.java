package com.xa.mass.runtime.worker.slot;

/**
 * Shared state-machine validation used by storage implementations.
 */
public final class WorkerScoreBandTransitionRules {

    private WorkerScoreBandTransitionRules() {
    }

    public static WorkerScoreBandTransitionStatus validate(long currentScore,
                                                           WorkerScoreBandTransitionCommand command) {
        if (command.expectedCurrentScore() != null && command.expectedCurrentScore() != currentScore) {
            return WorkerScoreBandTransitionStatus.STALE_OBSERVATION;
        }
        Long targetScore = command.targetScore();
        return switch (command.type()) {
            case RECOVERABLE_NEGATIVE ->
                    targetScore != null && WorkerScoreBand.isLowRecheck(targetScore)
                            ? WorkerScoreBandTransitionStatus.ACCEPTED
                            : WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
            case PARK ->
                    targetScore != null && WorkerScoreBand.isParked(targetScore)
                            ? WorkerScoreBandTransitionStatus.ACCEPTED
                            : WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
            case OWNER_VALIDATED_RECOVERY ->
                    targetScore != null && !WorkerScoreBand.isParked(targetScore)
                            ? WorkerScoreBandTransitionStatus.ACCEPTED
                            : WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
            case FUTURE_INTERVAL ->
                    targetScore != null && WorkerScoreBand.isTimeScore(targetScore)
                            ? WorkerScoreBandTransitionStatus.ACCEPTED
                            : WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
            case CLAIM_CLOSE -> validateClaimClose(currentScore, targetScore);
            case ATTEMPT_TIMEOUT -> validateAttemptTimeout(targetScore);
        };
    }

    public static boolean writesScore(WorkerScoreBandTransitionCommand command) {
        return command.targetScore() != null;
    }

    private static WorkerScoreBandTransitionStatus validateClaimClose(long currentScore, Long targetScore) {
        if (!WorkerScoreBand.isTimeScore(currentScore)) {
            return WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
        }
        if (targetScore == null || WorkerScoreBand.isLowRecheck(targetScore) || WorkerScoreBand.isParked(targetScore)) {
            return WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
        }
        return WorkerScoreBandTransitionStatus.ACCEPTED;
    }

    private static WorkerScoreBandTransitionStatus validateAttemptTimeout(Long targetScore) {
        return targetScore == null
                ? WorkerScoreBandTransitionStatus.ACCEPTED
                : WorkerScoreBandTransitionStatus.INVALID_TRANSITION;
    }
}
