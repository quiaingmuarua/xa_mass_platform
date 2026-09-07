package com.xa.mass.server.worker.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerResourceCommandServiceTest {

    private WorkerMatchingCatalog matchingCatalog;
    private WorkerResourceCommandService service;
    private WorkerBindingService bindings;
    private WorkerResourceCatalog workers;

    @BeforeEach
    void setUp() {
        matchingCatalog = mock(WorkerMatchingCatalog.class);
        bindings = mock(WorkerBindingService.class);
        workers = mock(WorkerResourceCatalog.class);
        service = new WorkerResourceCommandService(matchingCatalog, bindings, workers);
    }

    @Test
    void admitsBoundExistingWorkersAndBatchesByGroupWithoutCreatingResources() {
        Map<String, Map<String, String>> snapshots = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c", "wrong-adapter", "missing-group", "unknown")) {
            snapshots.put(id, Map.of("network.type", "wifi"));
        }
        List<String> ids = List.copyOf(snapshots.keySet());
        when(bindings.currentEndpointManagerIds(ids)).thenReturn(Map.of(
                "a", "adapter", "b", "adapter", "c", "adapter", "wrong-adapter", "other", "missing-group", "adapter"));
        when(workers.getWorkerGroupIds(ids)).thenReturn(Map.of(
                "a", "g1", "b", "g1", "c", "g2", "wrong-adapter", "g1"));
        when(matchingCatalog.upsertWorkerFactsBatch("g1", Map.of("a", snapshots.get("a"), "b", snapshots.get("b"))))
                .thenReturn(Map.of("a", result(MutationStatus.APPLIED), "b", result(MutationStatus.UNCHANGED)));
        when(matchingCatalog.upsertWorkerFactsBatch("g2", Map.of("c", snapshots.get("c"))))
                .thenReturn(Map.of("c", result(MutationStatus.APPLIED)));

        assertThat(service.replaceReportedProperties("adapter", snapshots)).containsExactlyInAnyOrder("a", "b", "c");
        verify(bindings).currentEndpointManagerIds(ids);
        verify(workers).getWorkerGroupIds(ids);
        verify(matchingCatalog).upsertWorkerFactsBatch("g1", Map.of("a", snapshots.get("a"), "b", snapshots.get("b")));
        verify(matchingCatalog).upsertWorkerFactsBatch("g2", Map.of("c", snapshots.get("c")));
        verifyNoMoreInteractions(bindings, workers, matchingCatalog);
    }

    @Test
    void missingIdentitiesNeverCreateMatchingFacts() {
        when(bindings.currentEndpointManagerIds(List.of("unknown"))).thenReturn(Map.of());
        when(workers.getWorkerGroupIds(List.of("unknown"))).thenReturn(Map.of());
        assertThat(service.replaceReportedProperties("adapter", Map.of("unknown", Map.of()))).isEmpty();
        verifyNoInteractions(matchingCatalog);
    }

    @Test
    void partialGroupFailureIsUnavailableWithoutRollbackOrOtherOwnerCalls() {
        Map<String, Map<String, String>> snapshots = new LinkedHashMap<>();
        snapshots.put("a", Map.of());
        snapshots.put("b", Map.of());
        when(bindings.currentEndpointManagerIds(List.of("a", "b"))).thenReturn(Map.of("a", "adapter", "b", "adapter"));
        when(workers.getWorkerGroupIds(List.of("a", "b"))).thenReturn(Map.of("a", "g1", "b", "g2"));
        when(matchingCatalog.upsertWorkerFactsBatch("g1", Map.of("a", Map.of())))
                .thenReturn(Map.of("a", result(MutationStatus.APPLIED)));
        when(matchingCatalog.upsertWorkerFactsBatch("g2", Map.of("b", Map.of())))
                .thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.replaceReportedProperties("adapter", snapshots))
                .isInstanceOfSatisfying(ServerException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
                    assertThat(error.operation()).isEqualTo("workerResource.replaceReportedProperties");
                });
        verify(matchingCatalog).upsertWorkerFactsBatch("g1", Map.of("a", Map.of()));
        verify(matchingCatalog).upsertWorkerFactsBatch("g2", Map.of("b", Map.of()));
        verifyNoMoreInteractions(matchingCatalog);
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
