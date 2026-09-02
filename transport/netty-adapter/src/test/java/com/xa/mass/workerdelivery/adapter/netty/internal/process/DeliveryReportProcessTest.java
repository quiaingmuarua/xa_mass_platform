package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryReportProcessTest {

    @Test
    void submitsExactlyTheReceivedBatchAndCompletesIt() {
        DeliveryReportRemoteApi remoteApi = mock(
                DeliveryReportRemoteApi.class
        );
        DeliveryReportProcess process = new DeliveryReportProcess(
                remoteApi,
                "adapter-1"
        );
        List<String> batch = List.of("one", "two");

        assertThat(process.process(batch)).isEqualTo(
                BatchProcessResult.completed()
        );

        verify(remoteApi).append("adapter-1", batch);
    }

    @Test
    void leavesTransientFailureClassificationToTheDispatcher() {
        DeliveryReportRemoteApi remoteApi = mock(
                DeliveryReportRemoteApi.class
        );
        WorkerDeliveryAdapterException failure = failure(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
        );
        doThrow(failure).when(remoteApi).append(
                "adapter-1",
                List.of("one")
        );
        DeliveryReportProcess process = new DeliveryReportProcess(
                remoteApi,
                "adapter-1"
        );

        assertThatThrownBy(() -> process.process(List.of("one")))
                .isSameAs(failure);
    }

    @Test
    void leavesProtocolFailureClassificationToTheDispatcher() {
        DeliveryReportRemoteApi remoteApi = mock(
                DeliveryReportRemoteApi.class
        );
        WorkerDeliveryAdapterException failure = failure(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR
        );
        doThrow(failure).when(remoteApi).append(
                "adapter-1",
                List.of("bad")
        );
        DeliveryReportProcess process = new DeliveryReportProcess(
                remoteApi,
                "adapter-1"
        );

        assertThatThrownBy(() -> process.process(List.of("bad")))
                .isSameAs(failure);
    }

    private static WorkerDeliveryAdapterException failure(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        return new WorkerDeliveryAdapterException(
                errorCode,
                "deliveryReport.submitRemote",
                null,
                null
        );
    }
}
