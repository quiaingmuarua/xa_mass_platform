package com.xa.mass.workerdelivery.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpWorkerDeliveryGatewayClientTest {

    private static final String COMMAND_ID =
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
        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                9_999_999_999_999L,
                "opaque-item"
        );
        respond(
                200,
                "{\"workerCommandsByWorkerId\":{\"worker-1\":"
                        + codec.encodeWorkerCommand(command)
                        + "}}"
        );

        var commands = client.consumeWorkerCommands(
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
        List<SeedResult> results = List.of(
                new SeedResult(COMMAND_ID, "context-1", "200", "null"),
                new SeedResult(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "context-2",
                        "3001",
                        null
                )
        );
        List<String> encodedResults = results.stream()
                .map(codec::encodeSeedResult)
                .toList();
        respond(
                202,
                "{\"acceptedCount\":1,\"rejectedCount\":1}"
        );

        client.appendResults(
                "adapter-1",
                SeedResultSource.WORKER,
                encodedResults
        );

        assertThat(requestPath).endsWith(
                "/adapter-1/results:append"
        );
        assertThat(Jsons.parseObject(requestBody))
                .containsEntry("source", "WORKER")
                .containsEntry("results", encodedResults);

        respond(
                202,
                "{\"acceptedCount\":1,\"rejectedCount\":0}"
        );
        assertThatThrownBy(() ->
                client.appendResults(
                        "adapter-1",
                        SeedResultSource.WORKER,
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
    void rejectsUnexpectedStatusAndMalformedResponses() {
        respond(503, "{}");
        assertThatThrownBy(() ->
                client.consumeWorkerCommands("adapter-1", 100)
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
                client.consumeWorkerCommands("adapter-1", 100)
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
        assertThatThrownBy(() -> client.appendResults(
                "adapter-1",
                SeedResultSource.WORKER,
                List.of(codec.encodeSeedResult(new SeedResult(
                            COMMAND_ID,
                            "context",
                            "200",
                            "null"
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
        assertThatThrownBy(() -> client.appendResults(
                "adapter-1",
                SeedResultSource.ADAPTER,
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
                client.consumeWorkerCommands("adapter-1", 0)
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
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
