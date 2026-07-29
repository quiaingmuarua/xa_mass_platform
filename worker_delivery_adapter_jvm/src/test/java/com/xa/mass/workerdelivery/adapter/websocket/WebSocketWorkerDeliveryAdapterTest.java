package com.xa.mass.workerdelivery.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
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

        Probe firstProbe = new Probe("worker-1");
        Probe secondProbe = new Probe("worker-1");
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

            WorkerCommandEnvelope firstCommand = command(
                    "a5e9e10d-f78b-469e-93ab-864b49c189c1"
            );
            WorkerCommandEnvelope secondCommand = command(
                    "9f0d983c-8010-4d59-a6d2-e8fedb8d0059"
            );
            firstGateway.batches.add(Map.of("worker-1", firstCommand));
            secondGateway.batches.add(Map.of("worker-1", secondCommand));

            assertThat(firstProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(secondProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
            assertThat(codec.decodeWorkerConnectionMessage(
                    firstProbe.messages.getFirst()
            )).isEqualTo(new TaskItemCommandMessage(firstCommand));
            assertThat(codec.decodeWorkerConnectionMessage(
                    secondProbe.messages.getFirst()
            )).isEqualTo(new TaskItemCommandMessage(secondCommand));
            assertThat(firstGateway.endpointManagerIds)
                    .containsOnly("websocket-1");
            assertThat(secondGateway.endpointManagerIds)
                    .containsOnly("websocket-2");

            SeedResult result = new SeedResult(
                    firstCommand.commandId(),
                    "context",
                    "200",
                    "null"
            );
            firstSocket.sendText(
                    codec.encodeWorkerConnectionMessage(
                            new TaskItemResultMessage(result)
                    ),
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(firstGateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(firstGateway.appendedResults)
                    .containsExactly(List.of(result));
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
    void interruptedShutdownUsesTheOwnerErrorCode() {
        WebSocketWorkerDeliveryAdapter adapter = adapter(
                "websocket-1",
                availablePort(),
                new FakeGateway()
        );
        adapter.start();

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
                    codec.encodeWorkerConnectionMessage(
                            new TaskItemResultMessage(new SeedResult(
                                    "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                                    "context",
                                    "200",
                                    "null"
                            ))
                    ),
                    true
            ).get(2, TimeUnit.SECONDS);
            assertThat(unbound.closed.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            Probe bound = new Probe("worker-1");
            WebSocket boundSocket = connect(port, bound);
            assertThat(bound.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            awaitBound(adapter);
            boundSocket.sendText(
                    codec.encodeWorkerConnectionBind(
                            new WorkerConnectionBind("worker-1")
                    ),
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
                                            + "/workers/worker-1"
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

    private static WebSocketWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            WorkerDeliveryGatewayClient gateway
    ) {
        return new WebSocketWorkerDeliveryAdapter(
                adapterId,
                gateway,
                new WorkerDeliveryCodec(),
                "127.0.0.1",
                port,
                Duration.ofMillis(10),
                100,
                4,
                100,
                1000,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static WorkerCommandEnvelope command(String commandId) {
        return new WorkerCommandEnvelope(
                commandId,
                WorkerMessageType.TASK_ITEM,
                System.currentTimeMillis() + 60_000,
                "opaque"
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
                Map<String, WorkerCommandEnvelope>
                > batches =
                new ConcurrentLinkedQueue<>();
        private final List<String> endpointManagerIds =
                new CopyOnWriteArrayList<>();
        private final List<List<SeedResult>> appendedResults =
                new CopyOnWriteArrayList<>();
        private final CountDownLatch resultAppended =
                new CountDownLatch(1);

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            endpointManagerIds.add(endpointManagerId);
            Map<String, WorkerCommandEnvelope> batch = batches.poll();
            return batch == null ? Map.of() : batch;
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            endpointManagerIds.add(endpointManagerId);
            appendedResults.add(List.copyOf(results));
            resultAppended.countDown();
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
            webSocket.sendText(
                    codec.encodeWorkerConnectionBind(
                            new WorkerConnectionBind(workerId)
                    ),
                    true
            ).whenComplete((ignored, error) -> {
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
}
