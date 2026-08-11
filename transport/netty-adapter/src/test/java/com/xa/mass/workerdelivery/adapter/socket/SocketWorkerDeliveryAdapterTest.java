package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SocketWorkerDeliveryAdapterTest {

    private static final String WORKER_ID = "server-issued-worker-id";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void closeTerminatesAnUnboundSocketChannel() throws Exception {
        int port = availablePort();
        SocketWorkerDeliveryAdapter adapter = adapter(
                port,
                new FakeGateway()
        );
        adapter.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2_000);
            awaitTracked(adapter);

            adapter.close();

            assertThat(socket.getInputStream().read()).isEqualTo(-1);
            assertThat(adapter.trackedConnectionCount()).isZero();
            assertThat(adapter.activeConnectionCount()).isZero();
        } finally {
            adapter.close();
        }
    }

    @Test
    void identifiesWithCrLfAndCompletesCommandResultRound()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        SocketWorkerDeliveryAdapter adapter = adapter(port, gateway);
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(adapter);
        manager.start();

        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
            socket.setSoTimeout(2_000);
            writer.write(identity(WORKER_ID));
            writer.write("\r\n");
            writer.flush();
            awaitActive(adapter);

            DeliveryCommand command = command();
            gateway.batches.add(Map.of(WORKER_ID, command));
            String commandLine = reader.readLine();
            assertThat(codec.decodeDeliveryCommand(commandLine))
                    .isEqualTo(command);

            DeliveryReport result = DeliveryReport.fromCommand(
                    command,
                    WORKER,
                    WORKER_ID,
                    "200",
                    "null"
            );
            String encodedResult = codec.encodeDeliveryReport(result);
            writer.write(encodedResult);
            writer.write('\n');
            writer.flush();

            assertThat(gateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(gateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
            assertThat(gateway.endpointManagerIds)
                    .containsOnly("socket-1");
        } finally {
            manager.close();
        }
    }

    @Test
    void invalidFirstMessageClosesAndRepeatedIdentityStaysLocal()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        SocketWorkerDeliveryAdapter adapter = adapter(
                port,
                gateway
        );
        adapter.start();
        try {
            assertClosedAfterLine(
                    port,
                    "{\"extra\":true,\"workerId\":\"" + WORKER_ID + "\"}"
            );
            assertClosedAfterLine(
                    port,
                    codec.encodeDeliveryReport(DeliveryReport.create(
                            WORKER,
                            WORKER_ID,
                            TASK,
                            "test.observe",
                            "200",
                            "null",
                            "context"
                    ))
            );

            try (Socket socket = new Socket("127.0.0.1", port);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                )) {
                socket.setSoTimeout(2_000);
                String identity = identity(WORKER_ID);
                writer.write(identity);
                writer.write('\n');
                writer.flush();
                awaitActive(adapter);

                writer.write(identity);
                writer.write('\n');
                writer.write("{bad-json\n");
                writer.write(codec.encodeDeliveryReport(result(
                        TASK,
                        "test.observe",
                        "23002",
                        "context"
                )) + "\n");
                writer.write(codec.encodeDeliveryReport(result(
                        SYSTEM,
                        "system.observe",
                        "200",
                        ""
                )) + "\n");
                writer.write(codec.encodeDeliveryReport(result(
                        ADAPTER,
                        "adapter.unknown",
                        "200",
                        ""
                )) + "\n");
                writer.write(codec.encodeDeliveryReport(resultFrom(
                        "another-worker",
                        TASK,
                        "test.observe",
                        "200",
                        "context"
                )) + "\n");
                String accepted = codec.encodeDeliveryReport(result(
                        TASK,
                        "test.observe",
                        "3302",
                        "context"
                ));
                writer.write(accepted);
                writer.write('\n');
                writer.flush();

                assertThat(gateway.resultAppended.await(
                        2,
                        TimeUnit.SECONDS
                )).isTrue();
                assertThat(gateway.appendedResults)
                        .containsExactly(List.of(accepted));
                assertThat(gateway.verifiedWorkerIds)
                        .containsExactly(WORKER_ID);
                assertThat(adapter.activeConnectionCount()).isEqualTo(1);
            }
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
        SocketWorkerDeliveryAdapter adapter = adapter(port, gateway);
        adapter.start();
        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
            socket.setSoTimeout(2_000);
            writer.write(identity(WORKER_ID));
            writer.write('\n');
            writer.flush();

            DeliveryCommand close = codec.decodeDeliveryCommand(
                    reader.readLine()
            );
            assertThat(close.src()).isEqualTo(ADAPTER);
            assertThat(close.dst()).isEqualTo(WORKER);
            assertThat(close.messageType())
                    .isEqualTo(WORKER_CONNECTION_CLOSE_EVENT_CODE);
            assertThat(close.payload()).isEqualTo("null");
            assertThat(close.forward()).isEmpty();
            assertThat(reader.readLine()).isNull();
            assertThat(adapter.activeConnectionCount()).isZero();
            assertThat(gateway.appendedResults).isEmpty();
        } finally {
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
        SocketWorkerDeliveryAdapter adapter = adapter(port, gateway);
        adapter.start();
        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
            socket.setSoTimeout(2_000);
            writer.write(identity(WORKER_ID));
            writer.write('\n');
            writer.flush();

            assertThat(reader.readLine()).isNull();
            assertThat(adapter.activeConnectionCount()).isZero();
        } finally {
            adapter.close();
        }
    }

    @Test
    void fullResultQueueClosesTheBoundChannel() throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        SocketWorkerDeliveryAdapter adapter = new SocketWorkerDeliveryAdapter(
                "socket-1",
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
        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
            socket.setSoTimeout(2_000);
            writer.write(identity(WORKER_ID));
            writer.write('\n');
            writer.flush();
            awaitActive(adapter);

            writer.write(codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-1"
            )) + "\n");
            writer.write(codec.encodeDeliveryReport(result(
                    TASK,
                    "test.observe",
                    "200",
                    "context-2"
            )) + "\n");
            writer.flush();

            assertThat(reader.readLine()).isNull();
            assertThat(adapter.activeConnectionCount()).isZero();
            assertThat(gateway.appendedResults).isEmpty();
        } finally {
            adapter.close();
        }
    }

    private void assertClosedAfterLine(int port, String line)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8
                        )
                )) {
            socket.setSoTimeout(2_000);
            writer.write(line);
            writer.write('\n');
            writer.flush();
            assertThat(reader.readLine()).isNull();
        }
    }

    private SocketWorkerDeliveryAdapter adapter(
            int port,
            WorkerDeliveryGatewayClient gateway
    ) {
        return new SocketWorkerDeliveryAdapter(
                "socket-1",
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

    private String identity(String workerId) {
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

    private static DeliveryCommand command() {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                System.currentTimeMillis() + 60_000,
                "{}",
                "context"
        );
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

    private static void awaitActive(SocketWorkerDeliveryAdapter adapter)
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

    private static void awaitTracked(SocketWorkerDeliveryAdapter adapter)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (adapter.trackedConnectionCount() == 1) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Worker channel was not tracked");
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

        private FakeGateway() {
            this(CompletableFuture.completedFuture(null));
        }

        private FakeGateway(
                CompletableFuture<Void> routeVerificationResponse
        ) {
            this.routeVerificationResponse = routeVerificationResponse;
        }

        @Override
        public Map<String, DeliveryCommand> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            endpointManagerIds.add(endpointManagerId);
            Map<String, DeliveryCommand> batch = batches.poll();
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
