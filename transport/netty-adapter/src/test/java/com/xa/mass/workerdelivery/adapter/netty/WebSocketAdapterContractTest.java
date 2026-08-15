package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebSocketAdapterContractTest {

    private static final String WORKER_PATH =
            "/api/v1/worker-delivery/websocket";

    private static final String WORKER_ID = "server-issued-worker-id";
    private final List<ScriptedHttpServer> httpServers =
            new CopyOnWriteArrayList<>();

    @AfterEach
    void closeHttpServers() {
        httpServers.forEach(ScriptedHttpServer::close);
    }

    @Test
    void closeTerminatesAnUnboundWebSocketChannel() throws Exception {
        int port = availablePort();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                new TestRemoteApi()
        );
        adapter.start();
        Probe probe = new Probe();
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();

            adapter.close();

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void binaryFrameIsRejectedByTheWebSocketProtocolBoundary()
            throws Exception {
        int port = availablePort();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                new TestRemoteApi()
        );
        adapter.start();
        Probe probe = new Probe();
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            socket.sendBinary(ByteBuffer.wrap(new byte[]{1}), true).join();

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closeStatusCode).isEqualTo(1003);
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void closeTerminatesAChannelWaitingForRouteVerification()
            throws Exception {
        int port = availablePort();
        CompletableFuture<Void> verification = new CompletableFuture<>();
        TestRemoteApi remoteApi = new TestRemoteApi(verification);
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            awaitVerification(remoteApi);

            adapter.close();

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            verification.complete(null);
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void reconnectReusesVerifiedRouteAndReceivesCachedCommand()
            throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        Probe firstProbe = new Probe(WORKER_ID);
        WebSocket first = connect(port, firstProbe);
        WebSocket reconnect = null;
        try {
            assertThat(firstProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            awaitRoutable(remoteApi, firstProbe);
            assertThat(remoteApi.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);

            first.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .get(2, TimeUnit.SECONDS);
            assertThat(firstProbe.closed.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            DeliveryCommand command = command(
                    "cached-during-disconnect"
            );
            remoteApi.batches.add(Map.of(WORKER_ID, command));
            awaitCommandConsumed(remoteApi);

            Probe reconnectProbe = new Probe(WORKER_ID);
            reconnect = connect(port, reconnectProbe);
            assertThat(reconnectProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            assertThat(remoteApi.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);
            assertThat(reconnectProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(new WorkerDeliveryCodec().decodeDeliveryCommand(
                    reconnectProbe.messages.getFirst()
            )).isEqualTo(command);
        } finally {
            first.abort();
            if (reconnect != null) {
                reconnect.abort();
            }
            adapter.close();
        }
    }

    @Test
    void twoAdaptersOwnDistinctPortsAndIsolateTheSameWorkerId()
            throws Exception {
        int firstPort = availablePort();
        int secondPort = availablePort();
        while (secondPort == firstPort) {
            secondPort = availablePort();
        }
        TestRemoteApi firstGateway = new TestRemoteApi();
        TestRemoteApi secondGateway = new TestRemoteApi();
        NettyWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                firstPort,
                firstGateway
        );
        NettyWorkerDeliveryAdapter second = adapter(
                "websocket-2",
                secondPort,
                secondGateway
        );
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(first);
        manager.register(second);
        manager.start();

        Probe firstProbe = new Probe(WORKER_ID);
        Probe secondProbe = new Probe(WORKER_ID);
        WebSocket firstSocket = connect(firstPort, firstProbe);
        WebSocket secondSocket = connect(
                secondPort,
                secondProbe
        );
        try {
            assertThat(firstProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(secondProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            DeliveryCommand firstCommand = command(
                    "a5e9e10d-f78b-469e-93ab-864b49c189c1"
            );
            DeliveryCommand secondCommand = command(
                    "9f0d983c-8010-4d59-a6d2-e8fedb8d0059"
            );
            firstGateway.batches.add(Map.of(WORKER_ID, firstCommand));
            secondGateway.batches.add(Map.of(WORKER_ID, secondCommand));

            assertThat(firstProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(secondProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
            assertThat(codec.decodeDeliveryCommand(
                    firstProbe.messages.getFirst()
            ))
                    .isEqualTo(firstCommand);
            assertThat(codec.decodeDeliveryCommand(
                    secondProbe.messages.getFirst()
            ))
                    .isEqualTo(secondCommand);
            assertThat(firstGateway.endpointManagerIds)
                    .containsOnly("websocket-1");
            assertThat(secondGateway.endpointManagerIds)
                    .containsOnly("websocket-2");

            DeliveryReport result = DeliveryReport.fromCommand(
                    firstCommand,
                    WORKER,
                    WORKER_ID,
                    "200",
                    "null"
            );
            String encodedResult = codec.encodeDeliveryReport(result);
            firstSocket.sendText(
                    encodedResult,
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(firstGateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(firstGateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
        } finally {
            firstSocket.abort();
            secondSocket.abort();
            manager.close();
        }

        assertThat(first.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
        assertThat(second.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void secondPortConflictRollsBackEveryRegisteredAdapter() {
        int port = availablePort();
        NettyWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                port,
                new TestRemoteApi()
        );
        NettyWorkerDeliveryAdapter second = adapter(
                "websocket-2",
                port,
                new TestRemoteApi()
        );
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(first);
        manager.register(second);

        assertThatThrownBy(manager::start)
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .LISTENER_START_FAILED
                            );
                            assertThat(error.operation())
                                    .isEqualTo("netty.startListener");
                            assertThat(error.getMessage())
                                    .contains("websocket-2");
                        }
                );

        assertThat(first.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
        assertThat(second.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void interruptedShutdownUsesTheOwnerErrorCode()
            throws InterruptedException {
        BlockingRemoteApi remoteApi = new BlockingRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                availablePort(),
                remoteApi.server.baseUri(),
                Duration.ofSeconds(30)
        );
        adapter.start();
        assertThat(remoteApi.consumeStarted.await(
                2,
                TimeUnit.SECONDS
        )).isTrue();

        WorkerDeliveryAdapterException failure;
        Thread.currentThread().interrupt();
        try {
            failure = org.junit.jupiter.api.Assertions.assertThrows(
                    WorkerDeliveryAdapterException.class,
                    adapter::close
            );
        } finally {
            Thread.interrupted();
        }

        assertThat(failure).isNotNull();
        assertThat(failure.errorCode()).isEqualTo(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED
        );
        assertThat(failure.operation())
                .isEqualTo("netty.close");
        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void requiresIdentityResultAndRejectsNonIdentityFirstMessage()
            throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        try {
            Probe unbound = new Probe();
            WebSocket unboundSocket = connect(port, unbound);
            assertThat(unbound.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            unboundSocket.sendText(
                    codec.encodeDeliveryReport(DeliveryReport.create(
                            WORKER,
                            WORKER_ID,
                            TASK,
                            "test.observe",
                            "200",
                            "null",
                            "context"
                    )),
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(unbound.closed.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            Probe identified = new Probe(WORKER_ID);
            WebSocket identifiedSocket = connect(port, identified);
            assertThat(identified.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            awaitRoutable(remoteApi, identified);
            identifiedSocket.sendText(
                    encodeIdentity(codec, WORKER_ID),
                    true
            ).get(2, TimeUnit.SECONDS);

            Probe oldPath = new Probe();
            assertThatThrownBy(() -> HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(
                            URI.create(
                                    "ws://127.0.0.1:" + port
                                            + WORKER_PATH
                                            + "/workers/" + WORKER_ID
                            ),
                            oldPath
                    )
                    .get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(
                            java.net.http.WebSocketHandshakeException.class
                    );
        } finally {
            adapter.close();
        }
    }

    @Test
    void hardRouteRejectionSendsCloseCommandThenCloses() throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi(failedVerification(
                WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED
        ));
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.message.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();

            DeliveryCommand close = new WorkerDeliveryCodec()
                    .decodeDeliveryCommand(probe.messages.getFirst());
            assertThat(close.src()).isEqualTo(ADAPTER);
            assertThat(close.dst()).isEqualTo(WORKER);
            assertThat(close.messageType())
                    .isEqualTo(WORKER_CONNECTION_CLOSE_EVENT_CODE);
            assertThat(close.payload()).isEqualTo("null");
            assertThat(close.forward()).isEmpty();
            assertThat(remoteApi.appendedResults).isEmpty();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void unavailableRouteVerificationClosesWithoutCommand()
            throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi(failedVerification(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE
        ));
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.messages).isEmpty();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void boundInvalidResultsAreDroppedAndNextTaskResultIsAccepted()
            throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            awaitRoutable(remoteApi, probe);

            send(socket, "{bad-json");
            send(socket, codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "23002",
                    "context"
            )));
            String systemAccepted = codec.encodeDeliveryReport(result(
                    SYSTEM,
                    "system.observe",
                    "200",
                    ""
            ));
            send(socket, systemAccepted);
            send(socket, codec.encodeDeliveryReport(result(
                    ADAPTER,
                    "adapter.unknown",
                    "200",
                    ""
            )));
            send(socket, codec.encodeDeliveryReport(resultFrom(
                    "another-worker",
                    TASK,
                    "test.observe",
                    "200",
                    "context"
            )));
            send(socket, encodeIdentity(codec, WORKER_ID));

            String accepted = codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "3302",
                    "context"
            ));
            send(socket, accepted);

            assertThat(remoteApi.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(remoteApi.appendedResults)
                    .containsExactly(List.of(systemAccepted, accepted));
            assertThat(remoteApi.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void fullResultQueueClosesTheBoundChannel() throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                remoteApi,
                Duration.ofSeconds(30),
                1
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            awaitRoutable(remoteApi, probe);
            send(socket, codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-1"
            )));
            send(socket, codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-2"
            )));

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(remoteApi.appendedResults).isEmpty();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    private static NettyWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            TestRemoteApi remoteApi
    ) {
        return adapter(
                adapterId,
                port,
                remoteApi,
                Duration.ofMillis(10),
                1000
        );
    }

    private static NettyWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            URI remoteApiBaseUrl,
            Duration remoteRequestTimeout
    ) {
        return (NettyWorkerDeliveryAdapter)
                NettyWorkerDeliveryAdapters.webSocket(
                        adapterId,
                        remoteApiBaseUrl,
                        remoteRequestTimeout,
                        "127.0.0.1",
                        port,
                        processConfigs(Duration.ofMillis(10), 1000),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                );
    }

    private static NettyWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            TestRemoteApi remoteApi,
            Duration reportSubmitInterval,
            int reportQueueCapacity
    ) {
        return (NettyWorkerDeliveryAdapter)
                NettyWorkerDeliveryAdapters.webSocket(
                adapterId,
                remoteApi.server.baseUri(),
                Duration.ofSeconds(2),
                "127.0.0.1",
                port,
                processConfigs(reportSubmitInterval, reportQueueCapacity),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static List<NettyAdapterProcessConfig> processConfigs(
            Duration reportSubmitInterval,
            int reportQueueCapacity
    ) {
        return List.of(
                new NettyAdapterProcessConfig.DeliveryCommand(
                        Duration.ofMillis(10),
                        100,
                        1000
                ),
                new NettyAdapterProcessConfig.DeliveryReport(
                        reportSubmitInterval,
                        reportQueueCapacity
                )
        );
    }

    private static DeliveryCommand command(String marker) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                System.currentTimeMillis() + 60_000,
                "{\"marker\":\"" + marker + "\"}",
                "context"
        );
    }

    private static WebSocket connect(
            int port,
            Probe probe
    ) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + port
                                        + WORKER_PATH
                        ),
                        probe
                )
                .get(2, TimeUnit.SECONDS);
    }

    private static void send(WebSocket socket, String message)
            throws Exception {
        socket.sendText(message, true).get(2, TimeUnit.SECONDS);
    }

    private static DeliveryReport result(
            com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
                    .DeliveryEndpoint dst,
            String messageType,
            String outcomeCode,
            String forward
    ) {
        return resultFrom(
                WORKER_ID,
                dst,
                messageType,
                outcomeCode,
                forward
        );
    }

    private static DeliveryReport resultFrom(
            String sourceId,
            com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
                    .DeliveryEndpoint dst,
            String messageType,
            String outcomeCode,
            String forward
    ) {
        return DeliveryReport.create(
                WORKER,
                sourceId,
                dst,
                messageType,
                outcomeCode,
                "null",
                forward
        );
    }

    private static void awaitRoutable(
            TestRemoteApi remoteApi,
            Probe probe
    ) throws InterruptedException {
        DeliveryCommand barrier = command("route-ready");
        remoteApi.batches.add(Map.of(WORKER_ID, barrier));
        assertThat(probe.message.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(new WorkerDeliveryCodec().decodeDeliveryCommand(
                probe.messages.getFirst()
        )).isEqualTo(barrier);
        probe.resetMessages();
    }

    private static void awaitCommandConsumed(TestRemoteApi remoteApi)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (remoteApi.batches.isEmpty()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Command was not cached by the Adapter");
    }

    private static void awaitVerification(TestRemoteApi remoteApi)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (!remoteApi.verifiedWorkerIds.isEmpty()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Worker route verification did not start");
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not reserve a test port",
                    error
            );
        }
    }

    private final class TestRemoteApi {

        private final ConcurrentLinkedQueue<
                Map<String, DeliveryCommand>
                > batches =
                new ConcurrentLinkedQueue<>();
        private final List<String> endpointManagerIds =
                new CopyOnWriteArrayList<>();
        private final List<List<String>> appendedResults =
                new CopyOnWriteArrayList<>();
        private final List<String> verifiedWorkerIds =
                new CopyOnWriteArrayList<>();
        private final CountDownLatch resultAppended =
                new CountDownLatch(1);
        private final CompletableFuture<Void> routeVerificationResponse;
        private final ScriptedHttpServer server;

        private TestRemoteApi() {
            this(CompletableFuture.completedFuture(null));
        }

        private TestRemoteApi(
                CompletableFuture<Void> routeVerificationResponse
        ) {
            this.routeVerificationResponse = routeVerificationResponse;
            server = new ScriptedHttpServer(this::handle);
            httpServers.add(server);
        }

        private Response handle(ScriptedHttpServer.Request request) {
            String endpointManagerId = endpointManagerId(request.rawPath());
            endpointManagerIds.add(endpointManagerId);
            if (request.rawPath().endsWith("/commands:consume")) {
                Map<String, DeliveryCommand> batch = batches.poll();
                Map<String, Object> encoded = new java.util.LinkedHashMap<>();
                if (batch != null) {
                    batch.forEach((workerId, command) -> encoded.put(
                            workerId,
                            Jsons.parseObject(new WorkerDeliveryCodec()
                                    .encodeDeliveryCommand(command))
                    ));
                }
                return new Response(200, Jsons.toJson(Map.of(
                        "commands",
                        encoded
                )));
            }
            if (request.rawPath().endsWith("/results:append")) {
                @SuppressWarnings("unchecked")
                List<String> results = (List<String>) Jsons.parseObject(
                        request.body()
                ).get("results");
                appendedResults.add(List.copyOf(results));
                resultAppended.countDown();
                return accepted(results.size());
            }
            String workerId = workerId(request.rawPath());
            verifiedWorkerIds.add(workerId);
            try {
                routeVerificationResponse.join();
                return new Response(204, "");
            } catch (CompletionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof WorkerDeliveryAdapterException failure
                        && failure.errorCode()
                        == WorkerDeliveryAdapterErrorCode
                        .WORKER_ROUTE_REJECTED) {
                    return new Response(409, "{}");
                }
                return new Response(503, "{}");
            }
        }

        private Response accepted(int count) {
            return new Response(202, Jsons.toJson(Map.of(
                    "acceptedCount",
                    count,
                    "rejectedCount",
                    0
            )));
        }

        private String endpointManagerId(String path) {
            String marker = "/endpoint-managers/";
            int start = path.indexOf(marker) + marker.length();
            int end = path.indexOf('/', start);
            return URLDecoder.decode(
                    path.substring(start, end),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        }

        private String workerId(String path) {
            String marker = "/workers/";
            int start = path.indexOf(marker) + marker.length();
            int end = path.indexOf(":verify-binding", start);
            return URLDecoder.decode(
                    path.substring(start, end),
                    java.nio.charset.StandardCharsets.UTF_8
            );
        }
    }

    private final class BlockingRemoteApi {

        private final CountDownLatch consumeStarted =
                new CountDownLatch(1);
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private BlockingRemoteApi() {
            httpServers.add(server);
        }

        private Response handle(ScriptedHttpServer.Request request)
                throws InterruptedException {
            if (!request.rawPath().endsWith("/commands:consume")) {
                return new Response(204, "");
            }
            consumeStarted.countDown();
            new CountDownLatch(1).await();
            return new Response(
                    200,
                    "{\"commands\":{}}"
            );
        }
    }

    private static final class Probe implements WebSocket.Listener {

        private final String workerId;
        private final WorkerDeliveryCodec codec =
                new WorkerDeliveryCodec();
        private final CountDownLatch opened = new CountDownLatch(1);
        private volatile CountDownLatch message = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final StringBuilder fragments = new StringBuilder();
        private volatile int closeStatusCode;

        private Probe(String workerId) {
            this.workerId = workerId;
        }

        private Probe() {
            this.workerId = null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (workerId == null) {
                opened.countDown();
                webSocket.request(1);
                return;
            }
            CompletionStage<WebSocket> sent = webSocket.sendText(
                    encodeIdentity(codec, workerId),
                    true
            );
            sent.whenComplete((ignored, error) -> {
                if (error == null) {
                    opened.countDown();
                    webSocket.request(1);
                }
            });
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
                message.countDown();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason
        ) {
            closeStatusCode = statusCode;
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        private void resetMessages() {
            messages.clear();
            message = new CountDownLatch(1);
        }
    }

    private static String encodeIdentity(
            WorkerDeliveryCodec codec,
            String workerId
    ) {
        return codec.encodeDeliveryReport(DeliveryReport.create(
                WORKER,
                workerId,
                ADAPTER,
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                "null",
                ""
        ));
    }

    private static CompletableFuture<Void> failedVerification(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        CompletableFuture<Void> failure = new CompletableFuture<>();
        failure.completeExceptionally(new WorkerDeliveryAdapterException(
                errorCode,
                "workerConnection.verifyRoute",
                "Route verification failed",
                null
        ));
        return failure;
    }
}
