package com.xa.mass.server.worker.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerProperties.MutationResult;
import com.xa.mass.workermatching.WorkerProperties.MutationStatus;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerResourceCommandServiceTest {

    private WorkerProperties workerProperties;
    private WorkerResourceCommandService service;
    private WorkerSchedulingService scheduling;

    @BeforeEach
    void setUp() {
        workerProperties = mock(WorkerProperties.class);
        scheduling = mock(WorkerSchedulingService.class);
        service = new WorkerResourceCommandService(workerProperties, scheduling);
    }

    @Test
    void mapsSuccessfulOwnerResultsToActionOutcomes() {
        when(workerProperties.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).thenReturn(result(MutationStatus.APPLIED));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).isEqualTo(ActionOutcome.applied());

        when(workerProperties.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenReturn(result(MutationStatus.UNCHANGED));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isEqualTo(ActionOutcome.unchanged());
        var order = inOrder(workerProperties, scheduling);
        order.verify(workerProperties).patchWorkerPlatformProperties("group-1", "worker-1", Map.of("region", "east"));
        order.verify(scheduling).invalidateCandidates("group-1", List.of("worker-1"));
        order.verify(workerProperties).patchWorkerPlatformProperties("group-1", "worker-1", Map.of());
        verifyNoMoreInteractions(scheduling);
    }

    @Test
    void mapsOwnerRejectionsWithoutExposingOwnerReason() {
        assertBusinessError(
                MutationStatus.NOT_FOUND,
                ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
        );
        assertBusinessError(
                MutationStatus.INVALID,
                ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST
        );
        assertBusinessError(
                MutationStatus.CONFLICT,
                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
        );
    }

    @Test
    void providerFailureIsUnavailable() {
        when(workerProperties.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenThrow(new IllegalStateException("Redis detail"));

        assertThatThrownBy(() -> service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isInstanceOfSatisfying(ServerException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE
            );
            assertThat(error.operation()).isEqualTo(
                    "workerResource.patchPlatformProperties"
            );
        });
        verifyNoInteractions(scheduling);
    }

    @Test
    void appliedPlatformPatchSurvivesInvalidationFailure() {
        var scores = mock(com.xa.mass.kernel.score.WorkerScoreCore.class);
        service = new WorkerResourceCommandService(workerProperties, new WorkerSchedulingService(scores));
        when(workerProperties.patchWorkerPlatformProperties("group-1", "worker-1", Map.of("region", "east")))
                .thenReturn(result(MutationStatus.APPLIED));
        when(scores.advancePastScoreTimesToNow("group-1", List.of("worker-1")))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThat(service.patchPlatformProperties("group-1", "worker-1", Map.of("region", "east")))
                .isEqualTo(ActionOutcome.applied());
        verify(scores).advancePastScoreTimesToNow("group-1", List.of("worker-1"));
    }

    private void assertBusinessError(
            MutationStatus status,
            ServerErrorCode expectedCode
    ) {
        when(workerProperties.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenReturn(new MutationResult(status, "owner detail"));

        assertThatThrownBy(() -> service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isInstanceOfSatisfying(ServerException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(expectedCode);
            assertThat(error.getMessage()).isEqualTo(
                    expectedCode.defaultMessage()
            );
        });
        verifyNoInteractions(scheduling);
    }

    private static MutationResult result(MutationStatus status) {
        return new MutationResult(status);
    }
}
