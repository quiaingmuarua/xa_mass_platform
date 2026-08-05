package com.xa.mass.workerdelivery.adapter.websocket;

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
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
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

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

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
            awaitBound(first);
            awaitBound(second);

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
    void requiresOneBindAndRejectsTheOldIdentityPath()
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

            Probe bound = new Probe(WORKER_ID);
            WebSocket boundSocket = connect(port, bound);
            assertThat(bound.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            awaitBound(adapter);
            boundSocket.sendText(
                    encodeBind(codec, WORKER_ID),
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(bound.closed.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

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
    void retainsOnePendingResultUntilBindingVerificationCompletes()
            throws Exception {
        int port = availablePort();
        CompletableFuture<Void> bindingResponse = new CompletableFuture<>();
        FakeGateway gateway = new FakeGateway(bindingResponse);
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                port,
                gateway
        );
        adapter.start();
        WorkerResult result = new WorkerResult(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                TASK,
                "test.observe",
                "200",
                "null",
                "context"
        );
        String encodedResult = new WorkerDeliveryCodec()
                .encodeWorkerResult(result);
        Probe probe = new Probe(WORKER_ID, encodedResult);
        WebSocket socket = connect(port, probe);
        try {
            assertThat(probe.opened.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(gateway.bindingVerified.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(adapter.activeConnectionCount()).isZero();
            assertThat(gateway.resultAppended.await(
                    100,
                    TimeUnit.MILLISECONDS
            )).isFalse();

            bindingResponse.complete(null);

            awaitBound(adapter);
            assertThat(gateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(gateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
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

    private static void awaitBound(WebSocketWorkerDeliveryAdapter adapter)
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
        private final CountDownLatch resultAppended =
                new CountDownLatch(1);
        private final CountDownLatch bindingVerified =
                new CountDownLatch(1);
        private final CompletableFuture<Void> bindingResponse;

        private FakeGateway() {
            this(CompletableFuture.completedFuture(null));
        }

        private FakeGateway(CompletableFuture<Void> bindingResponse) {
            this.bindingResponse = bindingResponse;
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
            bindingVerified.countDown();
            return bindingResponse;
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
        private final String pendingResult;
        private final WorkerDeliveryCodec codec =
                new WorkerDeliveryCodec();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch message = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final List<String> messages = new ArrayList<>();
        private final StringBuilder fragments = new StringBuilder();

        private Probe(String workerId) {
            this(workerId, null);
        }

        private Probe(String workerId, String pendingResult) {
            this.workerId = workerId;
            this.pendingResult = pendingResult;
        }

        private Probe() {
            this.workerId = null;
            this.pendingResult = null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (workerId == null) {
                opened.countDown();
                webSocket.request(1);
                return;
            }
            CompletionStage<WebSocket> sent = webSocket.sendText(
                    encodeBind(codec, workerId),
                    true
            );
            if (pendingResult != null) {
                sent = sent.thenCompose(ignored -> webSocket.sendText(
                        pendingResult,
                        true
                ));
            }
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

    private static String encodeBind(
            WorkerDeliveryCodec codec,
            String workerId
    ) {
        return codec.encodeWorkerConnectionBind(
                new WorkerConnectionBind(workerId)
        );
    }
}
