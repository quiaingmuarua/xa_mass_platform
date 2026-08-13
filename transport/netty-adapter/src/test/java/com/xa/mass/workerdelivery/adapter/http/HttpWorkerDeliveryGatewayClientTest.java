package com.xa.mass.workerdelivery.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpWorkerDeliveryGatewayClientTest {

    private static final String WORKER_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private HttpServer server;
    private volatile String responseBody;
    private volatile int responseStatus;
    private volatile String requestPath;
    private volatile String requestBody;
    private HttpWorkerDeliveryGatewayClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/", this::handle);
        server.start();
        client = new HttpWorkerDeliveryGatewayClient(
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                ),
                Duration.ofSeconds(2)
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void consumesTheExactEndpointBucketAndDecodesTheBatch() {
        DeliveryCommand command = DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                9_999_999_999_999L,
                "{}",
                "context"
        );
        respond(
                200,
                "{\"workerCommandsByWorkerId\":{\"worker-1\":"
                        + codec.encodeDeliveryCommand(command)
                        + "}}"
        );

        var commands = client.commandSource().consume(
                "adapter/one",
                100
        );

        assertThat(requestPath).isEqualTo(
                "/api/v1/worker-delivery/endpoint-managers/"
                        + "adapter%2Fone/commands:consume"
        );
        assertThat(requestBody)
                .isEqualTo("{\"limit\":100}");
        assertThat(commands).containsEntry("worker-1", command);
    }

    @Test
    void appendsOpaqueResultsWithOneBatchSourceAndRequiresFullAccounting() {
        List<DeliveryReport> results = List.of(
                DeliveryReport.create(
                        WORKER,
                        "worker-1",
                        TASK,
                        "test.observe",
                        "200",
                        "null",
                        "context-1"
                ),
                DeliveryReport.create(
                        ADAPTER,
                        "adapter-1",
                        TASK,
                        "test.observe",
                        Integer.toString(
                                WorkerDeliveryAdapterErrorCode
                                        .COMMAND_EXPIRED.code()
                        ),
                        "null",
                        "context-2"
                )
        );
        List<String> encodedResults = results.stream()
                .map(codec::encodeDeliveryReport)
                .toList();
        respond(
                202,
                "{\"acceptedCount\":1,\"rejectedCount\":1}"
        );

        client.resultIngress().ingress(
                "adapter-1",
                encodedResults
        );

        assertThat(requestPath).endsWith(
                "/adapter-1/results:append"
        );
        assertThat(Jsons.parseObject(requestBody))
                .containsEntry("results", encodedResults);

        respond(
                202,
                "{\"acceptedCount\":1,\"rejectedCount\":0}"
        );
        assertThatThrownBy(() ->
                client.resultIngress().ingress(
                        "adapter-1",
                        encodedResults
                )
        )
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .GATEWAY_PROTOCOL_ERROR
                            );
                            assertThat(error.operation())
                                    .isEqualTo(
                                            "gateway.decodeResultResponse"
                                    );
                        }
                );
    }

    @Test
    void verifiesTheExactWorkerBindingRouteWithoutARequestBody() {
        respond(204, "");

        client.routeVerifier().verify(
                "adapter/one",
                WORKER_ID
        ).toCompletableFuture().join();

        assertThat(requestPath).isEqualTo(
                "/api/v1/worker-delivery/endpoint-managers/"
                        + "adapter%2Fone/workers/"
                        + WORKER_ID
                        + ":verify-binding"
        );
        assertThat(requestBody).isEmpty();

        respond(409, "{}");
        assertThatThrownBy(() -> client.routeVerifier().verify(
                "adapter-1",
                WORKER_ID
        ).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WorkerDeliveryAdapterException.class)
                .satisfies(error -> assertThat(
                        ((WorkerDeliveryAdapterException) error.getCause())
                                .errorCode()
                ).isEqualTo(
                        WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED
                ));

        respond(302, "{}");
        assertThatThrownBy(() -> client.routeVerifier().verify(
                "adapter-1",
                WORKER_ID
        ).toCompletableFuture().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WorkerDeliveryAdapterException.class)
                .satisfies(error -> assertThat(
                        ((WorkerDeliveryAdapterException) error.getCause())
                                .errorCode()
                ).isEqualTo(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR
                ));
    }

    @Test
    void rejectsUnexpectedStatusAndMalformedResponses() {
        respond(503, "{}");
        assertThatThrownBy(() ->
                client.commandSource().consume("adapter-1", 100)
        )
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .GATEWAY_UNAVAILABLE
                            );
                            assertThat(error.operation())
                                    .isEqualTo("gateway.consumeCommands");
                        }
                );

        respond(
                200,
                "{\"workerCommandsByWorkerId\":{},\"nextCursor\":null}"
        );
        assertThatThrownBy(() ->
                client.commandSource().consume("adapter-1", 100)
        )
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .GATEWAY_PROTOCOL_ERROR
                            );
                            assertThat(error.operation()).isEqualTo(
                                    "gateway.decodeCommandResponse"
                            );
                        }
                );

        respond(
                202,
                "{\"acceptedCount\":\"one\",\"rejectedCount\":0}"
        );
        assertThatThrownBy(() -> client.resultIngress().ingress(
                "adapter-1",
                List.of(codec.encodeDeliveryReport(DeliveryReport.create(
                            WORKER,
                            "worker-1",
                            TASK,
                            "test.observe",
                            "200",
                            "null",
                            "context"
                    )))
        ))
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .GATEWAY_PROTOCOL_ERROR
                            );
                            assertThat(error.operation()).isEqualTo(
                                    "gateway.decodeResultResponse"
                            );
                        }
                );

        respond(400, "{}");
        assertThatThrownBy(() -> client.resultIngress().ingress(
                "adapter-1",
                List.of("opaque")
        ))
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                WorkerDeliveryAdapterErrorCode
                                        .GATEWAY_PROTOCOL_ERROR
                        )
                );
    }

    @Test
    void rejectsInvalidGatewayConfiguration() {
        assertThatThrownBy(() ->
                client.commandSource().consume("adapter-1", 0)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWorkerDeliveryGatewayClient(
                URI.create("/relative"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWorkerDeliveryGatewayClient(
                URI.create("redis://127.0.0.1:6379"),
                Duration.ofSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWorkerDeliveryGatewayClient(
                URI.create("http://127.0.0.1:18082"),
                Duration.ZERO
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestPath = exchange.getRequestURI().getRawPath();
        requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(
                responseStatus,
                responseStatus == 204 ? -1 : body.length
        );
        if (responseStatus != 204) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
