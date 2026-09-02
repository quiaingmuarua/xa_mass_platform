package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class WorkerDeliveryRemoteApiTest {

    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void ownsTheThreeFixedPathsAndTheirWireContracts() {
        DeliveryCommand command = command();
        String commandBody = "{\"worker-2\":"
                + CODEC.encodeDeliveryCommand(command)
                + ",\"worker-1\":"
                + CODEC.encodeDeliveryCommand(command)
                + "}";
        try (ScriptedHttpServer server = new ScriptedHttpServer(request -> {
            if (request.rawPath().endsWith("commands:consume")) {
                return new Response(200, commandBody);
            }
            if (request.rawPath().endsWith("results:append")) {
                return new Response(202,
                        "{\"acceptedCount\":2,\"rejectedCount\":0}");
            }
            return new Response(204, "");
        })) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);

            assertThat(remoteApi.consumeCommands("adapter/one", 3))
                    .containsExactly(
                            Map.entry("worker-2", command),
                            Map.entry("worker-1", command)
                    );
            remoteApi.appendReports(
                    "adapter/one",
                    List.of("report-1", "report-2")
            );
            remoteApi.verifyRoute("adapter/one", "worker two")
                    .toCompletableFuture()
                    .join();

            assertThat(server.requests()).hasSize(3);
            assertThat(server.requests().get(0)).satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "adapter%2Fone/commands:consume"
                );
                assertThat(request.body()).isEqualTo("3");
            });
            assertThat(server.requests().get(1)).satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "adapter%2Fone/results:append"
                );
                assertThat(request.body()).isEqualTo(
                        "[\"report-1\",\"report-2\"]"
                );
            });
            assertThat(server.requests().get(2)).satisfies(request -> {
                assertThat(request.rawPath()).isEqualTo(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "adapter%2Fone/workers/"
                                + "worker%20two:verify-binding"
                );
                assertThat(request.body()).isEmpty();
            });
        }
    }

    @Test
    void classifiesCommandStatusMalformedBodyAndOversizedResponse() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(503, "{}")
        )) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryCommand.consumeRemote"
            );
            server.handler(request -> new Response(409, "{}"));
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryCommand.consumeRemote"
            );
            server.handler(request -> new Response(302, "{}"));
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryCommand.consumeRemote"
            );
            server.handler(request -> new Response(200, "{\"bad\":true}"));
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryCommand.decodeRemoteResponse"
            );
            DeliveryCommand command = command();
            server.handler(request -> new Response(
                    200,
                    "{\"worker-1\":"
                            + CODEC.encodeDeliveryCommand(command)
                            + ",\"worker-2\":"
                            + CODEC.encodeDeliveryCommand(command)
                            + "}"
            ));
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryCommand.decodeRemoteResponse"
            );
        }
    }

    @Test
    void classifiesReportStatusAndIncompleteAcceptance() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(503, "{}")
        )) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryReport.submitRemote"
            );
            server.handler(request -> new Response(400, "{}"));
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.submitRemote"
            );
            server.handler(request -> new Response(302, "{}"));
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.submitRemote"
            );
            server.handler(request -> new Response(
                    202,
                    "{\"acceptedCount\":0,\"rejectedCount\":0}"
            ));
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.decodeRemoteResponse"
            );
        }
    }

    @Test
    void rejectsInvalidReportBatchesBeforeHttpSubmission() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(202,
                        "{\"acceptedCount\":0,\"rejectedCount\":0}")
        )) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);
            assertFailure(
                    () -> remoteApi.appendReports("adapter-1", List.of()),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.encodeRemoteRequest"
            );
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            Collections.nCopies(101, "report")
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.encodeRemoteRequest"
            );
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void classifiesRouteStatusesAtTheRouteMethod() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(404, "{}")
        )) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);
            assertRouteFailure(remoteApi, WORKER_ROUTE_REJECTED);
            server.handler(request -> new Response(503, "{}"));
            assertRouteFailure(remoteApi, REMOTE_API_UNAVAILABLE);
            server.handler(request -> new Response(302, "{}"));
            assertRouteFailure(remoteApi, REMOTE_API_PROTOCOL_ERROR);
        }
    }

    @Test
    void classifiesSyncAndAsyncNetworkFailuresAsUnavailable() {
        ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(204, "")
        );
        WorkerDeliveryRemoteApi remoteApi = remoteApi(server);
        server.close();

        assertFailure(
                () -> remoteApi.consumeCommands("adapter-1", 1),
                REMOTE_API_UNAVAILABLE,
                "deliveryCommand.consumeRemote"
        );
        assertFailure(
                () -> remoteApi.appendReports(
                        "adapter-1",
                        List.of("report")
                ),
                REMOTE_API_UNAVAILABLE,
                "deliveryReport.submitRemote"
        );
        assertRouteFailure(remoteApi, REMOTE_API_UNAVAILABLE);
    }

    @Test
    void appliesTheConfiguredTimeoutToSyncAndAsyncRequests() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(request -> {
            Thread.sleep(500);
            if (request.rawPath().endsWith("commands:consume")) {
                return new Response(200, "{}");
            }
            if (request.rawPath().endsWith("results:append")) {
                return new Response(202,
                        "{\"acceptedCount\":1,\"rejectedCount\":0}");
            }
            return new Response(204, "");
        })) {
            WorkerDeliveryRemoteApi remoteApi = new WorkerDeliveryRemoteApi(
                    server.baseUri(),
                    Duration.ofMillis(50),
                    CODEC
            );
            assertFailure(
                    () -> remoteApi.consumeCommands("adapter-1", 1),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryCommand.consumeRemote"
            );
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of("report")
                    ),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryReport.submitRemote"
            );
            assertRouteFailure(remoteApi, REMOTE_API_UNAVAILABLE);
        }
    }

    @Test
    void keepsBaseUrlAndTimeoutConfigurationInstanceLocal() {
        try (ScriptedHttpServer slow = new ScriptedHttpServer(request -> {
            Thread.sleep(250);
            return new Response(200, "{}");
        }); ScriptedHttpServer normal = new ScriptedHttpServer(
                request -> new Response(200, "{}")
        )) {
            WorkerDeliveryRemoteApi shortTimeout = new WorkerDeliveryRemoteApi(
                    slow.baseUri(),
                    Duration.ofMillis(40),
                    CODEC
            );
            WorkerDeliveryRemoteApi normalTimeout =
                    new WorkerDeliveryRemoteApi(
                            normal.baseUri(),
                            Duration.ofSeconds(1),
                            CODEC
                    );

            assertFailure(
                    () -> shortTimeout.consumeCommands("adapter-1", 1),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryCommand.consumeRemote"
            );
            assertThat(normalTimeout.consumeCommands("adapter-2", 1))
                    .isEmpty();
            assertThat(slow.requests()).hasSize(1);
            assertThat(normal.requests()).hasSize(1);
        }
    }

    @Test
    void rejectsInvalidInstanceConfiguration() {
        assertThatThrownBy(() -> new WorkerDeliveryRemoteApi(
                URI.create("/relative"),
                Duration.ofSeconds(1),
                CODEC
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryRemoteApi(
                URI.create("redis://127.0.0.1:6379"),
                Duration.ofSeconds(1),
                CODEC
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryRemoteApi(
                URI.create("http://127.0.0.1:18082?owner=other"),
                Duration.ofSeconds(1),
                CODEC
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryRemoteApi(
                URI.create("http://127.0.0.1:18082#fragment"),
                Duration.ofSeconds(1),
                CODEC
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerDeliveryRemoteApi(
                URI.create("http://127.0.0.1:18082"),
                Duration.ZERO,
                CODEC
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static DeliveryCommand command() {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                1234,
                "{}",
                "forward-1"
        );
    }

    private static WorkerDeliveryRemoteApi remoteApi(
            ScriptedHttpServer server
    ) {
        return new WorkerDeliveryRemoteApi(
                server.baseUri(),
                Duration.ofSeconds(2),
                CODEC
        );
    }

    private static void assertFailure(
            Runnable action,
            WorkerDeliveryAdapterErrorCode expectedCode,
            String expectedOperation
    ) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                WorkerDeliveryAdapterException.class,
                error -> {
                    assertThat(error.errorCode()).isEqualTo(expectedCode);
                    assertThat(error.operation()).isEqualTo(expectedOperation);
                }
        );
    }

    private static void assertRouteFailure(
            WorkerDeliveryRemoteApi remoteApi,
            WorkerDeliveryAdapterErrorCode expectedCode
    ) {
        assertThatThrownBy(() -> remoteApi.verifyRoute(
                "adapter-1",
                "worker-1"
        ).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WorkerDeliveryAdapterException.class)
                .cause()
                .satisfies(error -> {
                    WorkerDeliveryAdapterException classified =
                            (WorkerDeliveryAdapterException) error;
                    assertThat(classified.errorCode())
                            .isEqualTo(expectedCode);
                    assertThat(classified.operation())
                            .isEqualTo("workerConnection.verifyRoute");
                });
    }
}
