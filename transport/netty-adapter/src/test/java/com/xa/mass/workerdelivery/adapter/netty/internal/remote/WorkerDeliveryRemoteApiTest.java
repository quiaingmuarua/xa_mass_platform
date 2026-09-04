package com.xa.mass.workerdelivery.adapter.netty.internal.remote;

import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR;
import static com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
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
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerDeliveryRemoteApiTest {

    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void ownsTheTwoFixedPathsAndTheirWireContracts() {
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
            DeliveryReport first = report(TASK, "report-1");
            DeliveryReport second = report(TASK, "report-2");

            assertThat(remoteApi.consumeCommands("adapter/one", 3))
                    .containsExactly(
                            Map.entry("worker-2", command),
                            Map.entry("worker-1", command)
                    );
            remoteApi.appendReports(
                    "adapter/one",
                    List.of(first, second)
            );
            assertThat(server.requests()).hasSize(2);
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
                        Jsons.toJson(List.of(
                                CODEC.encodeDeliveryReportFields(first),
                                CODEC.encodeDeliveryReportFields(second)
                        ))
                );
            });
        }
    }

    @Test
    void submitsEverySupportedDestinationThroughTheSameReportPath() {
        try (ScriptedHttpServer server = new ScriptedHttpServer(
                request -> new Response(
                        202,
                        "{\"acceptedCount\":1,\"rejectedCount\":0}"
                )
        )) {
            WorkerDeliveryRemoteApi remoteApi = remoteApi(server);

            remoteApi.appendReports(
                    "adapter-1",
                    List.of(report(TASK, "task"))
            );
            remoteApi.appendReports(
                    "adapter-1",
                    List.of(report(SYSTEM, "system"))
            );
            remoteApi.appendReports(
                    "adapter-1",
                    List.of(report(KERNEL, "kernel"))
            );

            assertThat(server.requests()).hasSize(3).allSatisfy(
                    request -> assertThat(request.rawPath()).isEqualTo(
                            "/api/v1/worker-delivery/endpoint-managers/"
                                    + "adapter-1/results:append"
                    )
            );
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
                            List.of(report(TASK, "report"))
                    ),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryReport.submitRemote"
            );
            server.handler(request -> new Response(400, "{}"));
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of(report(TASK, "report"))
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.submitRemote"
            );
            server.handler(request -> new Response(302, "{}"));
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of(report(TASK, "report"))
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
                            List.of(report(TASK, "report"))
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
                            Collections.nCopies(
                                    101,
                                    report(TASK, "report")
                            )
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.encodeRemoteRequest"
            );
            assertThat(server.requests()).isEmpty();

            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of(
                                    report(TASK, "task"),
                                    report(SYSTEM, "system")
                            )
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.encodeRemoteRequest"
            );
            assertFailure(
                    () -> remoteApi.appendReports(
                            "adapter-1",
                            List.of(DeliveryReport.create(
                                    WORKER,
                                    "worker-1",
                                    ADAPTER,
                                    "test.report",
                                    "200",
                                    "{}",
                                    ""
                            ))
                    ),
                    REMOTE_API_PROTOCOL_ERROR,
                    "deliveryReport.encodeRemoteRequest"
            );
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void classifiesNetworkFailuresAsUnavailable() {
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
                        List.of(report(TASK, "report"))
                ),
                REMOTE_API_UNAVAILABLE,
                "deliveryReport.submitRemote"
        );
    }

    @Test
    void appliesTheConfiguredTimeoutToRequests() {
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
                            List.of(report(TASK, "report"))
                    ),
                    REMOTE_API_UNAVAILABLE,
                    "deliveryReport.submitRemote"
            );
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

    private static DeliveryReport report(
            DeliveryEndpoint destination,
            String payload
    ) {
        DeliveryEndpoint source = destination == KERNEL ? ADAPTER : WORKER;
        return DeliveryReport.create(
                source,
                source == ADAPTER ? "adapter-1" : "worker-1",
                destination,
                "test.report",
                "200",
                payload,
                destination == TASK ? "task-context" : "context"
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

}
