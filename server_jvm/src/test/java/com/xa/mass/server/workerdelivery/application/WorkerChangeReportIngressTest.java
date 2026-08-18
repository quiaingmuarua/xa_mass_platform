package com.xa.mass.server.workerdelivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerdelivery.workerchange.WorkerChangeInbox;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerChangeReportIngressTest {

    private static final String ADAPTER_ID = "adapter-1";
    private static final String GROUP_ID = "group-1";
    private static final String WORKER_ID = "worker-1";

    private WorkerChangeInbox inbox;
    private WorkerBindingService bindings;
    private WorkerChangeReportIngress ingress;

    @BeforeEach
    void setUp() {
        inbox = mock(WorkerChangeInbox.class);
        bindings = mock(WorkerBindingService.class);
        ingress = new WorkerChangeReportIngress(
                inbox,
                bindings
        );
    }

    @Test
    void validatesCurrentBindingBeforeAppendingRawEvidence() {
        DeliveryReport report = availability(WORKER_ID, true);
        when(bindings.currentEndpointManagerIds(List.of(WORKER_ID)))
                .thenReturn(Map.of(WORKER_ID, ADAPTER_ID));
        when(inbox.append(List.of(report))).thenReturn(1);

        assertThat(ingress.append(ADAPTER_ID, List.of(report)))
                .isEqualTo(new WorkerChangeReportIngress.AppendCounts(1, 0));
        verify(inbox).append(List.of(report));
    }

    @Test
    void rejectsMalformedOrForeignEvidenceBeforeOwnerReads() {
        List<DeliveryReport> invalid = List.of(
                DeliveryReport.create(
                        DeliveryEndpoint.WORKER,
                        WORKER_ID,
                        DeliveryEndpoint.SYSTEM,
                        "platform.adapter.worker-availability.changed",
                        "200",
                        payload(WORKER_ID, true),
                        "worker-change:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        "adapter-2",
                        DeliveryEndpoint.SYSTEM,
                        "platform.adapter.worker-availability.changed",
                        "200",
                        payload(WORKER_ID, true),
                        "worker-change:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        "platform.adapter.worker-availability.changed",
                        "200",
                        Jsons.toJson(Map.of(
                                "workerGroupId", GROUP_ID,
                                "workerId", WORKER_ID,
                                "available", true
                        )),
                        "worker-change:v1"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        ADAPTER_ID,
                        DeliveryEndpoint.SYSTEM,
                        "platform.adapter.worker-availability.changed",
                        "200",
                        payload(WORKER_ID, true),
                        "direct-call:v1:wrong"
                )
        );

        assertThat(ingress.append(ADAPTER_ID, invalid))
                .isEqualTo(new WorkerChangeReportIngress.AppendCounts(0, 4));
        verify(bindings, never()).currentEndpointManagerIds(anyList());
        verify(inbox, never()).append(anyList());
    }

    @Test
    void rejectsStaleOrMissingBindingEvidencePerItem() {
        DeliveryReport wrongBinding = availability("worker-binding", false);
        DeliveryReport missingBinding = availability("worker-missing", true);
        List<String> workerIds = List.of(
                "worker-binding",
                "worker-missing"
        );
        when(bindings.currentEndpointManagerIds(workerIds)).thenReturn(Map.of(
                "worker-binding", "adapter-2"
        ));

        assertThat(ingress.append(
                ADAPTER_ID,
                List.of(wrongBinding, missingBinding)
        )).isEqualTo(new WorkerChangeReportIngress.AppendCounts(0, 2));
        verify(inbox, never()).append(anyList());
    }

    @Test
    void capacityRejectionCountsTheUnacceptedSuffix() {
        DeliveryReport first = availability("worker-1", true);
        DeliveryReport second = availability("worker-2", false);
        List<String> workerIds = List.of("worker-1", "worker-2");
        when(bindings.currentEndpointManagerIds(workerIds)).thenReturn(Map.of(
                "worker-1", ADAPTER_ID,
                "worker-2", ADAPTER_ID
        ));
        when(inbox.append(List.of(first, second)))
                .thenReturn(1);

        assertThat(ingress.append(
                ADAPTER_ID,
                List.of(first, second)
        )).isEqualTo(new WorkerChangeReportIngress.AppendCounts(1, 1));
    }

    @Test
    void ownerFailureIsReportedAsWorkerDeliveryUnavailable() {
        DeliveryReport report = availability(WORKER_ID, true);
        when(bindings.currentEndpointManagerIds(List.of(WORKER_ID)))
                .thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> ingress.append(ADAPTER_ID, List.of(report)))
                .isInstanceOf(ServerException.class)
                .extracting(error -> ((ServerException) error).errorCode())
                .isEqualTo(ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE);
    }

    private static DeliveryReport availability(
            String workerId,
            boolean available
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                ADAPTER_ID,
                DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-availability.changed",
                "200",
                payload(workerId, available),
                "worker-change:v1"
        );
    }

    private static String payload(String workerId, boolean available) {
        return Jsons.toJson(Map.of(
                "workerId", workerId,
                "available", available
        ));
    }
}
