package com.xa.mass.server.workerscheduling;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class WorkerSchedulingService {

    private static final String PAUSE_OPERATION =
            "workerScheduling.pause";
    private static final String RESUME_OPERATION =
            "workerScheduling.resume";

    private final WorkerScoreCore workerScores;

    public WorkerSchedulingService(WorkerScoreCore workerScores) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
    }

    public WorkerScoreTransitionStatus pause(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            WorkerScoreTransitionResult result = requireResult(
                    workerScores.rewriteCurrentScores(
                            workerGroupId,
                            List.of(workerId),
                            WorkerScoreCore.PAUSE_TIME_MILLIS,
                            null
                    ),
                    workerId
            );
            if (result.status()
                    == WorkerScoreTransitionStatus.STALE
                    && result.score() != null) {
                return WorkerScoreTransitionStatus.NOOP;
            }
            return result.status();
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(PAUSE_OPERATION, error);
        }
    }

    public WorkerScoreTransitionStatus resume(
            String workerGroupId,
            String workerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        try {
            WorkerScoreCore.WorkerScoreState state = workerScores
                    .getScoreStates(workerGroupId, List.of(workerId))
                    .get(workerId);
            if (state == null) {
                return WorkerScoreTransitionStatus.STALE;
            }
            if (state.timeMillis()
                    != WorkerScoreCore.PAUSE_TIME_MILLIS) {
                return WorkerScoreTransitionStatus.NOOP;
            }
            return requireResult(
                    workerScores.releaseScoreHolds(
                            workerGroupId,
                            Map.of(workerId, state.score()),
                            System.currentTimeMillis()
                    ),
                    workerId
            ).status();
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(RESUME_OPERATION, error);
        }
    }

    private static WorkerScoreTransitionResult requireResult(
            Map<String, WorkerScoreTransitionResult> results,
            String workerId
    ) {
        if (results == null || results.get(workerId) == null) {
            throw new IllegalStateException(
                    "Worker score transition result is missing"
            );
        }
        return results.get(workerId);
    }

    private static ServerException unavailable(
            String operation,
            RuntimeException cause
    ) {
        return new ServerException(
                ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE,
                operation,
                "Worker scheduling score operation failed",
                cause
        );
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }
}
