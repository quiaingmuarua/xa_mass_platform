package com.xa.mass.server.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerSchedulingServiceTest {

    private static final String GROUP_ID = "group-1";
    private static final String WORKER_ID = "worker-1";

    private WorkerScoreCore workerScores;
    private WorkerSchedulingService service;

    @BeforeEach
    void setUp() {
        workerScores = mock(WorkerScoreCore.class);
        service = new WorkerSchedulingService(workerScores);
    }

    @Test
    void pauseUsesTheFixedOwnerHold() {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.TRANSITIONED, null)
        ));

        assertThat(service.pause(GROUP_ID, WORKER_ID))
                .isEqualTo(ActionOutcome.applied());
    }

    @Test
    void pauseClassifiesAnExistingFixedHoldAsNoop() {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.STALE, 123L)
        ));

        assertThat(service.pause(GROUP_ID, WORKER_ID))
                .isEqualTo(ActionOutcome.unchanged());
    }

    @Test
    void pauseMapsMissingAndInvalidOwnerResultsToBusinessErrors() {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.STALE, null)
        ));
        assertThatThrownBy(() -> service.pause(GROUP_ID, WORKER_ID))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
                        ));

        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.INVALID, null)
        ));
        assertThatThrownBy(() -> service.pause(GROUP_ID, WORKER_ID))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
                        ));
    }

    @Test
    void resumeReleasesOnlyTheExactPausedObservation() {
        long pausedScore = 19_999_999_999_804L;
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(Map.of(
                WORKER_ID,
                new WorkerScoreState(
                        WORKER_ID,
                        pausedScore,
                        WorkerScorePolarity.HOT_ACQUIRE,
                        WorkerScoreCore.PAUSE_TIME_MILLIS,
                        2,
                        0
                )
        ));
        when(workerScores.releaseScoreHolds(
                org.mockito.ArgumentMatchers.eq(GROUP_ID),
                org.mockito.ArgumentMatchers.eq(Map.of(
                        WORKER_ID,
                        pausedScore
                )),
                anyLong()
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.TRANSITIONED, 456L)
        ));
        long before = System.currentTimeMillis();

        ActionOutcome status = service.resume(
                GROUP_ID,
                WORKER_ID
        );
        long after = System.currentTimeMillis();

        assertThat(status)
                .isEqualTo(ActionOutcome.applied());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Long>> observations =
                ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Long> releaseTime = ArgumentCaptor.forClass(Long.class);
        verify(workerScores).releaseScoreHolds(
                org.mockito.ArgumentMatchers.eq(GROUP_ID),
                observations.capture(),
                releaseTime.capture()
        );
        assertThat(observations.getValue())
                .containsExactlyEntriesOf(Map.of(WORKER_ID, pausedScore));
        assertThat(releaseTime.getValue()).isBetween(before, after);
    }

    @Test
    void resumeMapsConcurrentReleaseChangesToAStateConflict() {
        long pausedScore = 19_999_999_999_804L;
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(Map.of(
                WORKER_ID,
                new WorkerScoreState(
                        WORKER_ID,
                        pausedScore,
                        WorkerScorePolarity.HOT_ACQUIRE,
                        WorkerScoreCore.PAUSE_TIME_MILLIS,
                        2,
                        0
                )
        ));

        for (WorkerScoreTransitionStatus status : List.of(
                WorkerScoreTransitionStatus.STALE,
                WorkerScoreTransitionStatus.INVALID
        )) {
            when(workerScores.releaseScoreHolds(
                    org.mockito.ArgumentMatchers.eq(GROUP_ID),
                    org.mockito.ArgumentMatchers.eq(Map.of(
                            WORKER_ID,
                            pausedScore
                    )),
                    anyLong()
            )).thenReturn(Map.of(
                    WORKER_ID,
                    result(status, pausedScore)
            ));

            assertThatThrownBy(() -> service.resume(GROUP_ID, WORKER_ID))
                    .isInstanceOfSatisfying(ServerException.class, error ->
                            assertThat(error.errorCode()).isEqualTo(
                                    ServerErrorCode
                                            .WORKER_RESOURCE_STATE_CONFLICT
                            ));
        }
    }

    @Test
    void resumeIsNoopWhenTheWorkerIsNotPaused() {
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(Map.of(
                WORKER_ID,
                new WorkerScoreState(
                        WORKER_ID,
                        123L,
                        WorkerScorePolarity.RECOVERY_RECHECK,
                        1_000L,
                        2,
                        1
                )
        ));

        assertThat(service.resume(GROUP_ID, WORKER_ID))
                .isEqualTo(ActionOutcome.unchanged());
        verify(workerScores, never()).releaseScoreHolds(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                anyLong()
        );
    }

    @Test
    void resumeRejectsAMissingWorkerResource() {
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        states.put(WORKER_ID, null);
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(states);

        assertThatThrownBy(() -> service.resume(GROUP_ID, WORKER_ID))
                .isInstanceOfSatisfying(ServerException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
                        ));
    }

    @Test
    void observeProjectsOneBoundedBatchWithoutExposingScores() {
        long nowMillis = System.currentTimeMillis();
        List<String> workerIds = List.of(
                "due",
                "held",
                "paused",
                "recovery",
                "cold",
                "missing"
        );
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        states.put("due", state(
                "due",
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.SLOT_MILLIS
        ));
        states.put("held", state(
                "held",
                WorkerScorePolarity.HOT_ACQUIRE,
                nowMillis + 60_000L
        ));
        states.put("paused", state(
                "paused",
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.PAUSE_TIME_MILLIS
        ));
        states.put("recovery", state(
                "recovery",
                WorkerScorePolarity.RECOVERY_RECHECK,
                3_000L
        ));
        states.put("cold", state(
                "cold",
                WorkerScorePolarity.RECOVERY_RECHECK,
                WorkerScoreCore.SLOT_MILLIS
        ));
        states.put("missing", null);
        when(workerScores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(states);

        WorkerSchedulingService.WorkerSchedulingObservation observation =
                service.observe(GROUP_ID, workerIds);

        assertThat(observation.readAtMillis()).isGreaterThanOrEqualTo(
                nowMillis
        );
        assertThat(observation.statesByWorkerId()).containsExactly(
                Map.entry("due", WorkerSchedulingService
                        .SchedulingState.HOT_SCORE_OVERDUE),
                Map.entry("held", WorkerSchedulingService
                        .SchedulingState.HELD_HOT),
                Map.entry("paused", WorkerSchedulingService
                        .SchedulingState.PAUSED),
                Map.entry("recovery", WorkerSchedulingService
                        .SchedulingState.RECOVERY),
                Map.entry("cold", WorkerSchedulingService
                        .SchedulingState.COLD),
                Map.entry("missing", WorkerSchedulingService
                        .SchedulingState.MISSING)
        );
        verify(workerScores).getScoreStates(GROUP_ID, workerIds);
    }

    @Test
    void observeRejectsInvalidIdentityBatchesBeforeReadingScores() {
        assertThatThrownBy(() -> service.observe(GROUP_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.observe(
                GROUP_ID,
                List.of(WORKER_ID, WORKER_ID)
        )).isInstanceOf(IllegalArgumentException.class);
        verify(workerScores, never()).getScoreStates(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void providerFailureUsesTheWorkerSchedulingErrorOwner() {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatThrownBy(() -> service.pause(GROUP_ID, WORKER_ID))
                .isInstanceOfSatisfying(ServerException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(
                            ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE
                    );
                    assertThat(error.operation())
                            .isEqualTo("workerScheduling.pause");
                });
    }

    private static WorkerScoreTransitionResult result(
            WorkerScoreTransitionStatus status,
            Long score
    ) {
        return new WorkerScoreTransitionResult(status, score);
    }

    private static WorkerScoreState state(
            String workerId,
            WorkerScorePolarity polarity,
            long timeMillis
    ) {
        return new WorkerScoreState(
                workerId,
                polarity.value() * timeMillis * WorkerScoreCore.TIME_SCALE,
                polarity,
                timeMillis,
                WorkerScoreCore.MIN_LANE_RANK,
                WorkerScoreCore.MIN_DIRTY
        );
    }
}
