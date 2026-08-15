package com.xa.mass.server.workerscheduling;

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
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
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
                .isEqualTo(WorkerScoreTransitionStatus.NOOP);
    }

    @Test
    void pausePreservesMissingAndInvalidOwnerResults() {
        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.STALE, null)
        ));
        assertThat(service.pause(GROUP_ID, WORKER_ID))
                .isEqualTo(WorkerScoreTransitionStatus.STALE);

        when(workerScores.rewriteCurrentScores(
                GROUP_ID,
                List.of(WORKER_ID),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        )).thenReturn(Map.of(
                WORKER_ID,
                result(WorkerScoreTransitionStatus.INVALID, null)
        ));
        assertThat(service.pause(GROUP_ID, WORKER_ID))
                .isEqualTo(WorkerScoreTransitionStatus.INVALID);
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

        WorkerScoreTransitionStatus status = service.resume(
                GROUP_ID,
                WORKER_ID
        );
        long after = System.currentTimeMillis();

        assertThat(status)
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
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
                .isEqualTo(WorkerScoreTransitionStatus.NOOP);
        verify(workerScores, never()).releaseScoreHolds(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                anyLong()
        );
    }

    @Test
    void resumeIsStaleWhenTheWorkerScoreIsMissing() {
        Map<String, WorkerScoreState> states = new LinkedHashMap<>();
        states.put(WORKER_ID, null);
        when(workerScores.getScoreStates(
                GROUP_ID,
                List.of(WORKER_ID)
        )).thenReturn(states);

        assertThat(service.resume(GROUP_ID, WORKER_ID))
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
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
}
