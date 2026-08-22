package com.xa.mass.server.workergroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerGroupRegistrationServiceTest {

    private WorkerResourceCatalog catalog;
    private WorkerGroupRegistrationService service;

    @BeforeEach
    void setUp() {
        catalog = mock(WorkerResourceCatalog.class);
        service = new WorkerGroupRegistrationService(catalog);
    }

    @Test
    void mapsCreatedAndEquivalentDeclarationsToPublicStatuses() {
        WorkerGroupDescriptor descriptor = new WorkerGroupDescriptor(
                "group-1",
                Map.of("capability", "string"),
                Set.of("event.b", "event.a")
        );
        when(catalog.registerWorkerGroup(descriptor)).thenReturn(
                new WorkerRuntimeResult(WorkerRuntimeStatus.OK),
                new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
        );

        assertThat(service.register(
                "group-1",
                descriptor.attributes(),
                List.of("event.b", "event.a")
        ).status()).isEqualTo("registered");
        assertThat(service.register(
                "group-1",
                descriptor.attributes(),
                List.of("event.a", "event.b")
        ).status()).isEqualTo("already_registered");
    }

    @Test
    void rejectsDuplicateEventsBeforeOwnerAndMapsConflict() {
        assertThatThrownBy(() -> service.register(
                "group-1",
                Map.of(),
                List.of("event", "event")
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.INVALID_WORKER_GROUP_REQUEST
                ));
        verify(catalog, never()).registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        );

        when(catalog.registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new WorkerRuntimeResult(
                WorkerRuntimeStatus.CONFLICT,
                "different descriptor"
        ));
        assertThatThrownBy(() -> service.register(
                "group-1",
                Map.of(),
                List.of("event")
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.WORKER_GROUP_REGISTRATION_CONFLICT
                ));
    }

    @Test
    void mapsOwnerCorruptionAndFailureToRegistrationUnavailable() {
        when(catalog.registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new WorkerRuntimeResult(
                WorkerRuntimeStatus.INVALID,
                "stored value is invalid"
        ));
        assertThatThrownBy(() -> service.register(
                "group-1",
                Map.of(),
                List.of("event")
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE
                ));

        when(catalog.registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        )).thenThrow(new IllegalStateException("redis unavailable"));
        assertThatThrownBy(() -> service.register(
                "group-1",
                Map.of(),
                List.of("event")
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE
                ));
    }
}
