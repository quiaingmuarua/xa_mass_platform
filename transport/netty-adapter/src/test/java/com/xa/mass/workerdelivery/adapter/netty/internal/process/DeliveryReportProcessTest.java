package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient.ResultIngress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryReportProcessTest {

    @Test
    void unavailableBatchRetriesBeforeNewReports() {
        RecordingIngress ingress = new RecordingIngress();
        ingress.failures.add(unavailable());
        DeliveryReportProcess process = new DeliveryReportProcess(
                ingress,
                "adapter-1",
                4
        );

        assertThat(process.acceptor().ingress(List.of("report-1")))
                .isEqualTo(ACCEPTED);
        process.round();
        assertThat(process.acceptor().ingress(List.of("report-2")))
                .isEqualTo(ACCEPTED);
        process.round();
        process.round();

        assertThat(ingress.attempts).containsExactly(
                List.of("report-1"),
                List.of("report-1"),
                List.of("report-2")
        );
    }

    @Test
    void protocolFailureDropsBatchAndAllowsLaterReports() {
        RecordingIngress ingress = new RecordingIngress();
        ingress.failures.add(protocolFailure());
        DeliveryReportProcess process = new DeliveryReportProcess(
                ingress,
                "adapter-1",
                4
        );

        process.acceptor().ingress(List.of("bad-report"));
        process.round();
        process.acceptor().ingress(List.of("next-report"));
        process.round();

        assertThat(ingress.attempts).containsExactly(
                List.of("bad-report"),
                List.of("next-report")
        );
    }

    @Test
    void acceptorHidesQueueStatusAndAppliesSoftCapacity() {
        DeliveryReportProcess process = new DeliveryReportProcess(
                new RecordingIngress(),
                "adapter-1",
                2
        );

        assertThat(process.acceptor().ingress(List.of("one", "two")))
                .isEqualTo(ACCEPTED);
        assertThat(process.acceptor().ingress(List.of("three")))
                .isEqualTo(FULL);
    }

    @Test
    void completedCloseRejectsLateReportsAndFlushesOnce() {
        RecordingIngress ingress = new RecordingIngress();
        DeliveryReportProcess process = new DeliveryReportProcess(
                ingress,
                "adapter-1",
                2
        );
        process.acceptor().ingress(List.of("report-1"));

        process.stopIngress();
        assertThat(process.acceptor().ingress(List.of("late")))
                .isEqualTo(CLOSED);
        process.finishCloseAfterSchedulerStop();
        process.finishCloseAfterSchedulerStop();

        assertThat(ingress.attempts).containsExactly(List.of("report-1"));
    }

    private static WorkerDeliveryAdapterException unavailable() {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                "gateway.appendResults",
                "unavailable",
                null
        );
    }

    private static WorkerDeliveryAdapterException protocolFailure() {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                "gateway.appendResults",
                "protocol failure",
                null
        );
    }

    private static final class RecordingIngress implements ResultIngress {

        private final ArrayDeque<RuntimeException> failures =
                new ArrayDeque<>();
        private final List<List<String>> attempts = new ArrayList<>();

        @Override
        public void ingress(
                String endpointManagerId,
                List<String> encodedDeliveryReports
        ) {
            attempts.add(List.copyOf(encodedDeliveryReports));
            RuntimeException failure = failures.pollFirst();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
