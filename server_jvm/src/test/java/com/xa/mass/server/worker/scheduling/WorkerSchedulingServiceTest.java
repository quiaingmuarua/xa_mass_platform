package com.xa.mass.server.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerSchedulingChangeStatus;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerSchedulingObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.SchedulingState;
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
    void candidateInvalidationAcceptsMissingMembersAndKeepsFailuresBestEffort() {
        List<String> ids = List.of("a", "b", "c", "d");
        when(workerScores.sealCurrentScoreHolds(GROUP_ID, ids)).thenReturn(Map.of(
                "a", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 201L),
                "b", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, 201L),
                "c", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, null),
                "d", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID, null)
        ), Map.of()).thenThrow(new IllegalStateException("unavailable"));
        for (int i = 0; i < 3; i++) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.invalidateCandidates(GROUP_ID, ids));
        }
        verify(workerScores, org.mockito.Mockito.times(3)).sealCurrentScoreHolds(GROUP_ID, ids);
        org.mockito.Mockito.verifyNoMoreInteractions(workerScores);
    }

    @Test
    void pauseAndResumeOnlyMapKernelOutcomes() {
        for (boolean pause : List.of(true, false)) {
            for (WorkerSchedulingChangeStatus status : WorkerSchedulingChangeStatus.values()) {
                if (pause) when(workerScores.pauseScheduling(GROUP_ID, WORKER_ID)).thenReturn(status);
                else when(workerScores.resumeScheduling(GROUP_ID, WORKER_ID)).thenReturn(status);
                java.util.function.Supplier<ActionOutcome> action = () -> pause
                        ? service.pause(GROUP_ID, WORKER_ID) : service.resume(GROUP_ID, WORKER_ID);
                switch (status) {
                    case APPLIED -> assertThat(action.get()).isEqualTo(ActionOutcome.applied());
                    case UNCHANGED -> assertThat(action.get()).isEqualTo(ActionOutcome.unchanged());
                    case MISSING -> assertThatThrownBy(action::get).isInstanceOf(ServerException.class)
                            .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_NOT_FOUND);
                    case CONFLICT -> assertThatThrownBy(action::get).isInstanceOf(ServerException.class)
                            .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);
                }
            }
        }
        verify(workerScores, org.mockito.Mockito.times(4)).pauseScheduling(GROUP_ID, WORKER_ID);
        verify(workerScores, org.mockito.Mockito.times(4)).resumeScheduling(GROUP_ID, WORKER_ID);
        org.mockito.Mockito.verifyNoMoreInteractions(workerScores);
    }

    @Test
    void observeReturnsTheKernelBatchWithoutReinterpretingStatesOrTime() {
        var states = new LinkedHashMap<String, SchedulingState>();
        for (SchedulingState state : SchedulingState.values()) states.put(state.name(), state);
        var ids = List.copyOf(states.keySet());
        var observation = new WorkerSchedulingObservation(12_345, states);
        when(workerScores.observeSchedulingStates(GROUP_ID, ids)).thenReturn(observation);
        assertThat(service.observe(GROUP_ID, ids)).isSameAs(observation);
        verify(workerScores).observeSchedulingStates(GROUP_ID, ids);
        org.mockito.Mockito.verifyNoMoreInteractions(workerScores);
    }

    @Test
    void observeRejectsInvalidInputsBeforeOwnerAccess() {
        for (List<String> ids : List.of(List.<String>of(), List.of(" "), List.of("w", "w"),
                java.util.stream.IntStream.range(0,101).mapToObj(i -> "w" + i).toList())) {
            assertThatThrownBy(() -> service.observe(GROUP_ID, ids)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.observe(GROUP_ID, null)).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(workerScores);
    }

    @Test
    void incompleteOwnerObservationIsUnavailableRatherThanInventingMissingStates() {
        var nullState = new LinkedHashMap<String, SchedulingState>();
        nullState.put(WORKER_ID, null);
        when(workerScores.observeSchedulingStates(GROUP_ID, List.of(WORKER_ID)))
                .thenReturn(null, new WorkerSchedulingObservation(10, Map.of()),
                        new WorkerSchedulingObservation(10, Map.of("another", SchedulingState.MISSING)),
                        new WorkerSchedulingObservation(10, nullState));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.observe(GROUP_ID, List.of(WORKER_ID)))
                    .isInstanceOf(ServerException.class).extracting("errorCode")
                    .isEqualTo(ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE);
        }
    }

    @Test
    void unavailableAndAbsentMutationResultsKeepTheExistingError() {
        when(workerScores.pauseScheduling(GROUP_ID, WORKER_ID)).thenReturn(null)
                .thenThrow(new IllegalStateException("Redis unavailable"));
        when(workerScores.resumeScheduling(GROUP_ID, WORKER_ID)).thenReturn(null)
                .thenThrow(new IllegalStateException("Redis unavailable"));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.pause(GROUP_ID, WORKER_ID)).isInstanceOf(ServerException.class)
                    .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE);
            assertThatThrownBy(() -> service.resume(GROUP_ID, WORKER_ID)).isInstanceOf(ServerException.class)
                    .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_SCHEDULING_UNAVAILABLE);
        }
    }
}
