package com.xa.mass.runtime.worker.slot;

import java.util.Objects;

public record WorkerScoreBandTransitionCommand(
        WorkerScoreBandTransitionType type,
        String homeBucketId,
        String workerId,
        Long targetScore,
        Long expectedCurrentScore,
        String reasonCode,
        String ownerAction,
        String sourceType,
        long observedAtMillis
) {

    public WorkerScoreBandTransitionCommand {
        type = Objects.requireNonNull(type, "type");
        homeBucketId = requireNonBlank(homeBucketId, "homeBucketId");
        workerId = requireNonBlank(workerId, "workerId");
        reasonCode = normalizeNullable(reasonCode);
        ownerAction = normalizeNullable(ownerAction);
        sourceType = normalizeNullable(sourceType);
        observedAtMillis = Math.max(0L, observedAtMillis);
    }

    public static WorkerScoreBandTransitionCommand recoverableNegative(String homeBucketId,
                                                                       String workerId,
                                                                       long nextRecheckAtMillis,
                                                                       String reasonCode,
                                                                       long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.RECOVERABLE_NEGATIVE,
                homeBucketId,
                workerId,
                WorkerScoreBand.lowRecheckScore(nextRecheckAtMillis),
                null,
                reasonCode,
                "LOW_RECHECK",
                "WORKER_RUNTIME",
                observedAtMillis
        );
    }

    public static WorkerScoreBandTransitionCommand park(String homeBucketId,
                                                        String workerId,
                                                        long parkedScore,
                                                        String reasonCode,
                                                        long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.PARK,
                homeBucketId,
                workerId,
                parkedScore,
                null,
                reasonCode,
                "PARK",
                "WORKER_RUNTIME",
                observedAtMillis
        );
    }

    public static WorkerScoreBandTransitionCommand futureInterval(String homeBucketId,
                                                                  String workerId,
                                                                  long untilEpochMillis,
                                                                  String reasonCode,
                                                                  long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.FUTURE_INTERVAL,
                homeBucketId,
                workerId,
                WorkerScoreBand.futureScore(untilEpochMillis),
                null,
                reasonCode,
                "FUTURE_INTERVAL",
                "WORKER_RUNTIME",
                observedAtMillis
        );
    }

    public static WorkerScoreBandTransitionCommand ownerValidatedRecovery(String homeBucketId,
                                                                          String workerId,
                                                                          long score,
                                                                          String reasonCode,
                                                                          long observedAtMillis) {
        return ownerValidatedRecovery(homeBucketId, workerId, null, score, reasonCode, observedAtMillis);
    }

    public static WorkerScoreBandTransitionCommand ownerValidatedRecovery(String homeBucketId,
                                                                          String workerId,
                                                                          Long expectedCurrentScore,
                                                                          long score,
                                                                          String reasonCode,
                                                                          long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.OWNER_VALIDATED_RECOVERY,
                homeBucketId,
                workerId,
                score,
                expectedCurrentScore,
                reasonCode,
                "RECOVER",
                "WORKER_RUNTIME",
                observedAtMillis
        );
    }

    public static WorkerScoreBandTransitionCommand claimClose(String homeBucketId,
                                                              String workerId,
                                                              long expectedCurrentScore,
                                                              long score,
                                                              String reasonCode,
                                                              long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.CLAIM_CLOSE,
                homeBucketId,
                workerId,
                score,
                expectedCurrentScore,
                reasonCode,
                "CLAIM_CLOSE",
                "ENGINE",
                observedAtMillis
        );
    }

    public static WorkerScoreBandTransitionCommand attemptTimeout(String homeBucketId,
                                                                  String workerId,
                                                                  Long targetScore,
                                                                  String reasonCode,
                                                                  long observedAtMillis) {
        return new WorkerScoreBandTransitionCommand(
                WorkerScoreBandTransitionType.ATTEMPT_TIMEOUT,
                homeBucketId,
                workerId,
                targetScore,
                null,
                reasonCode,
                "ATTEMPT_TIMEOUT",
                "ENGINE",
                observedAtMillis
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
