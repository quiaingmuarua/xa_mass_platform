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
import com.xa.mass.server.taskdata.WorkerGroupTaskCallRegistrationService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallRegistrationService
        .Registration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerGroupRegistrationServiceTest {

    private WorkerResourceCatalog catalog;
    private WorkerGroupTaskCallRegistrationService taskCallRegistrations;
    private WorkerGroupRegistrationService service;

    @BeforeEach
    void setUp() {
        catalog = mock(WorkerResourceCatalog.class);
        taskCallRegistrations = mock(
                WorkerGroupTaskCallRegistrationService.class
        );
        service = new WorkerGroupRegistrationService(
                catalog,
                taskCallRegistrations
        );
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
        when(taskCallRegistrations.register("group-1")).thenReturn(
                new Registration("group-1", "scenario-rpc-group-1", true),
                new Registration("group-1", "scenario-rpc-group-1", false)
        );

        var created = service.register(
                "group-1",
                descriptor.attributes(),
                List.of("event.b", "event.a")
        );
        assertThat(created.status()).isEqualTo("registered");
        assertThat(created.taskId()).isEqualTo("scenario-rpc-group-1");
        var existing = service.register(
                "group-1",
                descriptor.attributes(),
                List.of("event.a", "event.b")
        );
        assertThat(existing.status()).isEqualTo("already_registered");
        assertThat(existing.taskId()).isEqualTo("scenario-rpc-group-1");
    }

    @Test
    void anEquivalentGroupBackfillsItsMissingTaskCallRegistration() {
        when(catalog.registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP));
        when(taskCallRegistrations.register("group-1")).thenReturn(
                new Registration("group-1", "scenario-rpc-group-1", true)
        );

        assertThat(service.register(
                "group-1",
                Map.of(),
                List.of("event")
        ).status()).isEqualTo("registered");
    }

    @Test
    void aTaskCallFailureLeavesTheExactGroupRegistrationRetryable() {
        ServerException failure = new ServerException(
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "taskCall.register",
                null,
                null
        );
        when(catalog.registerWorkerGroup(
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(
                new WorkerRuntimeResult(WorkerRuntimeStatus.OK),
                new WorkerRuntimeResult(WorkerRuntimeStatus.NOOP)
        );
        when(taskCallRegistrations.register("group-1"))
                .thenThrow(failure)
                .thenReturn(new Registration(
                        "group-1",
                        "scenario-rpc-group-1",
                        true
                ));

        assertThatThrownBy(() -> service.register(
                "group-1",
                Map.of(),
                List.of("event")
        )).isSameAs(failure);
        assertThat(service.register(
                "group-1",
                Map.of(),
                List.of("event")
        ).status()).isEqualTo("registered");
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
        verify(taskCallRegistrations, never()).register(
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
        verify(taskCallRegistrations, never()).register(
                org.mockito.ArgumentMatchers.any()
        );
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
        verify(taskCallRegistrations, never()).register(
                org.mockito.ArgumentMatchers.any()
        );

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
