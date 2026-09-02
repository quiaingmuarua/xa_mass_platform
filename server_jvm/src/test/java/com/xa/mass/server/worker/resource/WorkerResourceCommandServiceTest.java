package com.xa.mass.server.worker.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerResourceCommandServiceTest {

    private WorkerMatchingCatalog matchingCatalog;
    private WorkerResourceCommandService service;

    @BeforeEach
    void setUp() {
        matchingCatalog = mock(WorkerMatchingCatalog.class);
        service = new WorkerResourceCommandService(matchingCatalog);
    }

    @Test
    void mapsSuccessfulOwnerResultsToActionOutcomes() {
        when(matchingCatalog.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).thenReturn(result(MutationStatus.APPLIED));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).isEqualTo(ActionOutcome.applied());

        when(matchingCatalog.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenReturn(result(MutationStatus.UNCHANGED));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isEqualTo(ActionOutcome.unchanged());
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
        when(matchingCatalog.patchWorkerPlatformProperties(
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
    }

    private void assertBusinessError(
            MutationStatus status,
            ServerErrorCode expectedCode
    ) {
        when(matchingCatalog.patchWorkerPlatformProperties(
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
    }

    private static MutationResult result(MutationStatus status) {
        return new MutationResult(status);
    }
}
