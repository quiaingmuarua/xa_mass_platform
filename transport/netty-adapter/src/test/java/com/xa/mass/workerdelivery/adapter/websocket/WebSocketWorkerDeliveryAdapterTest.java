package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketWorkerDeliveryAdapterTest {

    private static final String WORKER_ID = "server-issued-worker-id";

    @Test
    void twoAdaptersOwnDistinctPortsAndIsolateTheSameWorkerId()
            throws Exception {
        int firstPort = availablePort();
        int secondPort = availablePort();
        while (secondPort == firstPort) {
            secondPort = availablePort();
        }
        FakeGateway firstGateway = new FakeGateway();
        FakeGateway secondGateway = new FakeGateway();
        WebSocketWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                firstPort,
                firstGateway
        );
        WebSocketWorkerDeliveryAdapter second = adapter(
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
            awaitActive(first);
            awaitActive(second);

            WorkerCommand firstCommand = command(
                    "a5e9e10d-f78b-469e-93ab-864b49c189c1"
            );
            WorkerCommand secondCommand = command(
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
            assertThat(codec.decodeWorkerCommand(
                    firstProbe.messages.getFirst()
            ))
                    .isEqualTo(firstCommand);
            assertThat(codec.decodeWorkerCommand(
                    secondProbe.messages.getFirst()
            ))
                    .isEqualTo(secondCommand);
            assertThat(firstGateway.endpointManagerIds)
                    .containsOnly("websocket-1");
            assertThat(secondGateway.endpointManagerIds)
                    .containsOnly("websocket-2");

            WorkerResult result = new WorkerResult(
                    firstCommand.messageId(),
                    TASK,
                    firstCommand.messageType(),
                    "200",
                    "null",
                    firstCommand.forward()
            );
            String encodedResult = codec.encodeWorkerResult(result);
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
        WebSocketWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                port,
                new FakeGateway()
        );
        WebSocketWorkerDeliveryAdapter second = adapter(
                "websocket-2",
                port,
                new FakeGateway()
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
                                    .isEqualTo("websocket.startListener");
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
        BlockingGateway gateway = new BlockingGateway();
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                availablePort(),
                gateway
        );
        adapter.start();
        assertThat(gateway.consumeStarted.await(
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
                .isEqualTo("websocket.stopScheduler");
        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void requiresIdentityResultAndRejectsNonIdentityFirstMessage()
            throws Exception {
        int port = availablePort();
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                new FakeGateway()
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
                    codec.encodeWorkerResult(new WorkerResult(
                            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
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
            awaitActive(adapter);
            identifiedSocket.sendText(
                    encodeIdentity(codec, WORKER_ID),
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(adapter.activeConnectionCount()).isEqualTo(1);

            Probe oldPath = new Probe();
            assertThatThrownBy(() -> HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(
                            URI.create(
                                    "ws://127.0.0.1:" + port
                                            + WorkerWebSocketHandler
                                            .WORKER_PATH
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
        FakeGateway gateway = new FakeGateway(failedVerification(
                WorkerDeliveryAdapterErrorCode.WORKER_ROUTE_REJECTED
        ));
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                gateway
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.message.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();

            WorkerCommand close = new WorkerDeliveryCodec()
                    .decodeWorkerCommand(probe.messages.getFirst());
            assertThat(close.src()).isEqualTo(ADAPTER);
            assertThat(close.dst()).isEqualTo(WORKER);
            assertThat(close.messageType())
                    .isEqualTo(WORKER_CONNECTION_CLOSE_EVENT_CODE);
            assertThat(close.payload()).isEqualTo("null");
            assertThat(close.forward()).isEmpty();
            assertThat(adapter.activeConnectionCount()).isZero();
            assertThat(gateway.appendedResults).isEmpty();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void unavailableRouteVerificationClosesWithoutCommand()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway(failedVerification(
                WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE
        ));
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                gateway
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.messages).isEmpty();
            assertThat(adapter.activeConnectionCount()).isZero();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void boundInvalidResultsAreDroppedAndNextTaskResultIsAccepted()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                gateway
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            awaitActive(adapter);

            send(socket, "{bad-json");
            send(socket, codec.encodeWorkerResult(result(
                    TASK,
                    "test.observe",
                    "23002",
                    "context"
            )));
            send(socket, codec.encodeWorkerResult(result(
                    SYSTEM,
                    "system.observe",
                    "200",
                    ""
            )));
            send(socket, codec.encodeWorkerResult(result(
                    ADAPTER,
                    "adapter.unknown",
                    "200",
                    ""
            )));
            send(socket, encodeIdentity(codec, WORKER_ID));

            String accepted = codec.encodeWorkerResult(result(
                    TASK,
                    "test.observe",
                    "3302",
                    "context"
            ));
            send(socket, accepted);

            assertThat(gateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(gateway.appendedResults)
                    .containsExactly(List.of(accepted));
            assertThat(gateway.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);
            assertThat(adapter.activeConnectionCount()).isEqualTo(1);
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    @Test
    void fullResultQueueClosesTheBoundChannel() throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        WebSocketWorkerDeliveryAdapter adapter = new WebSocketWorkerDeliveryAdapter(
                "websocket-1",
                gateway,
                "127.0.0.1",
                port,
                Duration.ofMillis(10),
                100,
                1000,
                Duration.ofSeconds(30),
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        adapter.start();
        Probe probe = new Probe(WORKER_ID);
        WebSocket socket = connect(port, probe);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            awaitActive(adapter);
            send(socket, codec.encodeWorkerResult(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-1"
            )));
            send(socket, codec.encodeWorkerResult(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-2"
            )));

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(adapter.activeConnectionCount()).isZero();
            assertThat(gateway.appendedResults).isEmpty();
        } finally {
            socket.abort();
            adapter.close();
        }
    }

    private static WebSocketWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            WorkerDeliveryGatewayClient gateway
    ) {
        return new WebSocketWorkerDeliveryAdapter(
                adapterId,
                gateway,
                "127.0.0.1",
                port,
                Duration.ofMillis(10),
                100,
                1000,
                Duration.ofMillis(10),
                1000,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static WorkerCommand command(String messageId) {
        return new WorkerCommand(
                messageId,
                TASK,
                WORKER,
                "test.observe",
                System.currentTimeMillis() + 60_000,
                "{}",
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
                                        + WorkerWebSocketHandler.WORKER_PATH
                        ),
                        probe
                )
                .get(2, TimeUnit.SECONDS);
    }

    private static void send(WebSocket socket, String message)
            throws Exception {
        socket.sendText(message, true).get(2, TimeUnit.SECONDS);
    }

    private static WorkerResult result(
            com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
                    .WorkerMessageEndpoint dst,
            String messageType,
            String outcomeCode,
            String forward
    ) {
        return new WorkerResult(
                java.util.UUID.randomUUID().toString(),
                dst,
                messageType,
                outcomeCode,
                "null",
                forward
        );
    }

    private static void awaitActive(WebSocketWorkerDeliveryAdapter adapter)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (adapter.activeConnectionCount() == 1) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Worker connection was not bound");
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

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ConcurrentLinkedQueue<
                Map<String, WorkerCommand>
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

        private FakeGateway() {
            this(CompletableFuture.completedFuture(null));
        }

        private FakeGateway(
                CompletableFuture<Void> routeVerificationResponse
        ) {
            this.routeVerificationResponse = routeVerificationResponse;
        }

        @Override
        public Map<String, WorkerCommand> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            endpointManagerIds.add(endpointManagerId);
            Map<String, WorkerCommand> batch = batches.poll();
            return batch == null ? Map.of() : batch;
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<String> results
        ) {
            endpointManagerIds.add(endpointManagerId);
            appendedResults.add(List.copyOf(results));
            resultAppended.countDown();
        }

        @Override
        public java.util.concurrent.CompletionStage<Void>
        verifyWorkerRoute(String endpointManagerId, String workerId) {
            verifiedWorkerIds.add(workerId);
            return routeVerificationResponse;
        }
    }

    private static final class BlockingGateway
            implements WorkerDeliveryGatewayClient {

        private final CountDownLatch consumeStarted =
                new CountDownLatch(1);

        @Override
        public Map<String, WorkerCommand> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            consumeStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<String> results
        ) {
        }

        @Override
        public java.util.concurrent.CompletionStage<Void>
        verifyWorkerRoute(String endpointManagerId, String workerId) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    null
            );
        }
    }

    private static final class Probe implements WebSocket.Listener {

        private final String workerId;
        private final WorkerDeliveryCodec codec =
                new WorkerDeliveryCodec();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch message = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final List<String> messages = new ArrayList<>();
        private final StringBuilder fragments = new StringBuilder();

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
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String encodeIdentity(
            WorkerDeliveryCodec codec,
            String workerId
    ) {
        return codec.encodeWorkerResult(new WorkerResult(
                "5ca82f99-2398-4927-a814-c88ff47a5466",
                ADAPTER,
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                workerId,
                ""
        ));
    }

    private static CompletableFuture<Void> failedVerification(
            WorkerDeliveryAdapterErrorCode errorCode
    ) {
        CompletableFuture<Void> failure = new CompletableFuture<>();
        failure.completeExceptionally(new WorkerDeliveryAdapterException(
                errorCode,
                "gateway.verifyWorkerRoute",
                "Route verification failed",
                null
        ));
        return failure;
    }
}
