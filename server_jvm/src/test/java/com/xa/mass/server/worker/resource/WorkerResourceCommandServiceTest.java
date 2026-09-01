package com.xa.mass.server.worker.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService.PatchStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerResourceCommandServiceTest {

    private WorkerResourceCatalog workerCatalog;
    private WorkerResourceCommandService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        service = new WorkerResourceCommandService(workerCatalog);
    }

    @Test
    void mapsSuccessfulOwnerResultsToNaturalStatuses() {
        when(workerCatalog.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).thenReturn(result(WorkerRuntimeStatus.OK));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of("region", "east")
        )).isEqualTo(PatchStatus.UPDATED);

        when(workerCatalog.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenReturn(result(WorkerRuntimeStatus.NOOP));
        assertThat(service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isEqualTo(PatchStatus.UNCHANGED);
    }

    @Test
    void mapsOwnerRejectionsWithoutExposingOwnerReason() {
        assertBusinessError(
                WorkerRuntimeStatus.NOT_FOUND,
                ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
        );
        assertBusinessError(
                WorkerRuntimeStatus.INVALID,
                ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST
        );
        assertBusinessError(
                WorkerRuntimeStatus.CONFLICT,
                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
        );
        assertBusinessError(
                WorkerRuntimeStatus.STALE,
                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
        );
        assertBusinessError(
                WorkerRuntimeStatus.REJECTED,
                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
        );
    }

    @Test
    void providerFailureIsUnavailable() {
        when(workerCatalog.patchWorkerPlatformProperties(
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
            WorkerRuntimeStatus status,
            ServerErrorCode expectedCode
    ) {
        when(workerCatalog.patchWorkerPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).thenReturn(new WorkerRuntimeResult(status, "owner detail"));

        assertThatThrownBy(() -> service.patchPlatformProperties(
                "group-1", "worker-1", Map.of()
        )).isInstanceOfSatisfying(ServerException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(expectedCode);
            assertThat(error.getMessage()).isEqualTo(
                    expectedCode.defaultMessage()
            );
        });
    }

    private static WorkerRuntimeResult result(WorkerRuntimeStatus status) {
        return new WorkerRuntimeResult(status);
    }
}
