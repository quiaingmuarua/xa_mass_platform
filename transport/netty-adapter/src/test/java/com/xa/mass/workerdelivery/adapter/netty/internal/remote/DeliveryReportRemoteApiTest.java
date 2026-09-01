package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryReportRemoteApiTest {

    @Test
    void ownsUnifiedReportPathAndCompleteAcceptanceContract() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(202,
                        "{\"acceptedCount\":2,\"rejectedCount\":0}")
        )) {
            DeliveryReportRemoteApi remoteApi = remoteApi(server);

            remoteApi.append(
                    "adapter/one",
                    List.of("report-1", "report-2")
            );

            assertThat(server.requests()).hasSize(1);
            assertThat(server.requests().get(0).rawPath()).isEqualTo(
                    "/api/v1/worker-delivery/endpoint-managers/"
                            + "adapter%2Fone/results:append"
            );
            assertThat(server.requests().get(0).body()).isEqualTo(
                    "{\"results\":[\"report-1\",\"report-2\"]}"
            );
        }
    }

    @Test
    void classifiesStatusAndMalformedResponseAtTheReportOwner() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(503, "{}")
        )) {
            DeliveryReportRemoteApi remoteApi = remoteApi(server);
            assertFailure(
                    () -> remoteApi.append(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_UNAVAILABLE
            );
            server.handler(request -> new Response(400, "{}"));
            assertFailure(
                    () -> remoteApi.append(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR
            );
            server.handler(request -> new Response(
                    202,
                    "{\"acceptedCount\":0,\"rejectedCount\":0}"
            ));
            assertFailure(
                    () -> remoteApi.append(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR
            );
        }
    }

    @Test
    void rejectsEmptyAndOversizedBatchesBeforeHttpSubmission() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(202,
                        "{\"acceptedCount\":0,\"rejectedCount\":0}")
        )) {
            DeliveryReportRemoteApi remoteApi = remoteApi(server);

            assertFailure(
                    () -> remoteApi.append("adapter-1", List.of()),
                    REMOTE_API_PROTOCOL_ERROR
            );
            assertFailure(
                    () -> remoteApi.append(
                            "adapter-1",
                            Collections.nCopies(101, "report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR
            );

            assertThat(server.requests()).isEmpty();
        }
    }

    private static DeliveryReportRemoteApi remoteApi(
            ScriptedHttpServer server
    ) {
        return new DeliveryReportRemoteApi(new WorkerDeliveryHttpClient(
                server.baseUri(),
                Duration.ofSeconds(2)
        ));
    }

    private static void assertFailure(
            Runnable action,
            WorkerDeliveryAdapterErrorCode expected
    ) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                WorkerDeliveryAdapterException.class,
                error -> assertThat(error.errorCode()).isEqualTo(expected)
        );
    }
}
