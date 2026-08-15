package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.CLOSED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryReportProcessTest {

    @Test
    void unavailableBatchRetriesBeforeNewReports() {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(503, "{}"));
            DeliveryReportProcess process = process(peer, 4);

            assertThat(process.ingress(List.of("report-1")))
                    .isEqualTo(ACCEPTED);
            process.round();
            assertThat(process.ingress(List.of("report-2")))
                    .isEqualTo(ACCEPTED);
            process.round();
            process.round();

            assertThat(peer.attempts).containsExactly(
                    List.of("report-1"),
                    List.of("report-1"),
                    List.of("report-2")
            );
        }
    }

    @Test
    void protocolFailureDropsBatchAndAllowsLaterReports() {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(400, "{}"));
            DeliveryReportProcess process = process(peer, 4);

            process.ingress(List.of("bad-report"));
            process.round();
            process.ingress(List.of("next-report"));
            process.round();

            assertThat(peer.attempts).containsExactly(
                    List.of("bad-report"),
                    List.of("next-report")
            );
        }
    }

    @Test
    void malformedAcceptedResponseDropsOnlyItsBatch() {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(
                    202,
                    "{\"acceptedCount\":0,\"rejectedCount\":0}"
            ));
            DeliveryReportProcess process = process(peer, 4);

            process.ingress(List.of("bad-accounting"));
            process.round();
            process.ingress(List.of("next-report"));
            process.round();

            assertThat(peer.attempts).containsExactly(
                    List.of("bad-accounting"),
                    List.of("next-report")
            );
        }
    }

    @Test
    void outOfRangeAcceptedCountsAreProtocolFailure() {
        try (ReportPeer peer = new ReportPeer()) {
            peer.responses.add(new Response(
                    202,
                    "{\"acceptedCount\":4294967297,\"rejectedCount\":0}"
            ));
            DeliveryReportProcess process = process(peer, 4);

            process.ingress(List.of("bad-count"));
            process.round();
            process.ingress(List.of("next-report"));
            process.round();

            assertThat(peer.attempts).containsExactly(
                    List.of("bad-count"),
                    List.of("next-report")
            );
        }
    }

    @Test
    void ingressHidesQueueStorageAndAppliesSoftCapacity() {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(peer, 2);

            assertThat(process.ingress(List.of("one", "two")))
                    .isEqualTo(ACCEPTED);
            assertThat(process.ingress(List.of("three")))
                    .isEqualTo(FULL);
        }
    }

    @Test
    void completedCloseRejectsLateReportsAndFlushesOnce() {
        try (ReportPeer peer = new ReportPeer()) {
            DeliveryReportProcess process = process(peer, 2);
            process.ingress(List.of("report-1", "control-report"));

            process.quiesce();
            assertThat(process.ingress(List.of("late")))
                    .isEqualTo(CLOSED);
            process.finishAfterSchedulerStop();
            process.finishAfterSchedulerStop();

            assertThat(peer.attempts).containsExactly(
                    List.of("report-1", "control-report")
            );
        }
    }

    private static DeliveryReportProcess process(
            ReportPeer peer,
            int capacity
    ) {
        return new DeliveryReportProcess(
                new DeliveryReportRemoteApi(new WorkerDeliveryHttpClient(
                        peer.server.baseUri(),
                        Duration.ofSeconds(2)
                )),
                "adapter-1",
                capacity
        );
    }

    private static final class ReportPeer implements AutoCloseable {

        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final List<List<String>> attempts = new ArrayList<>();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private synchronized Response handle(
                ScriptedHttpServer.Request request
        ) {
            assertThat(request.rawPath()).endsWith("/results:append");
            Map<String, Object> body = Jsons.parseObject(request.body());
            @SuppressWarnings("unchecked")
            List<String> results = (List<String>) body.get("results");
            attempts.add(List.copyOf(results));
            Response scripted = responses.pollFirst();
            if (scripted != null) {
                return scripted;
            }
            return new Response(202, Jsons.toJson(Map.of(
                    "acceptedCount",
                    results.size(),
                    "rejectedCount",
                    0
            )));
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
