package com.xa.mass.workerdelivery.adapter.netty;

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

class SocketAdapterContractTest {

    private static final String WORKER_ID = "server-issued-worker-id";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void closeTerminatesAnUnboundSocketChannel() throws Exception {
        int port = availablePort();
        NettyWorkerDeliveryAdapter adapter = adapter(
                port,
                new FakeGateway()
        );
        adapter.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2_000);

            adapter.close();

            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } finally {
            adapter.close();
        }
    }

    @Test
    void identifiesWithCrLfAndCompletesCommandResultRound()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        NettyWorkerDeliveryAdapter adapter = adapter(port, gateway);
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
    void reconnectReusesVerifiedRouteAndReceivesCachedCommand()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        NettyWorkerDeliveryAdapter adapter = adapter(port, gateway);
        adapter.start();
        try {
            try (Socket first = new Socket("127.0.0.1", port);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    first.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(
                                    first.getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                writer.write(identity(WORKER_ID));
                writer.write('\n');
                writer.flush();
                first.setSoTimeout(2_000);
                awaitRoutable(gateway, reader);
                assertThat(gateway.verifiedWorkerIds)
                        .containsExactly(WORKER_ID);
                first.shutdownOutput();
                assertThat(reader.readLine()).isNull();
            }

            DeliveryCommand command = command();
            gateway.batches.add(Map.of(WORKER_ID, command));
            awaitCommandConsumed(gateway);

            try (Socket reconnect = new Socket("127.0.0.1", port);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    reconnect.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    );
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(
                                    reconnect.getOutputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )) {
                reconnect.setSoTimeout(3_000);
                writer.write(identity(WORKER_ID));
                writer.write('\n');
                writer.flush();

                assertThat(gateway.verifiedWorkerIds)
                        .containsExactly(WORKER_ID);
                assertThat(codec.decodeDeliveryCommand(reader.readLine()))
                        .isEqualTo(command);
            }
        } finally {
            adapter.close();
        }
    }

    @Test
    void invalidFirstMessageClosesAndRepeatedIdentityStaysLocal()
            throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        NettyWorkerDeliveryAdapter adapter = adapter(
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
                awaitRoutable(gateway, reader);

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
        NettyWorkerDeliveryAdapter adapter = adapter(port, gateway);
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
        NettyWorkerDeliveryAdapter adapter = adapter(port, gateway);
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
        } finally {
            adapter.close();
        }
    }

    @Test
    void fullResultQueueClosesTheBoundChannel() throws Exception {
        int port = availablePort();
        FakeGateway gateway = new FakeGateway();
        NettyWorkerDeliveryAdapter adapter = adapter(
                port,
                gateway,
                Duration.ofSeconds(30),
                1
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
            awaitRoutable(gateway, reader);

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

    private NettyWorkerDeliveryAdapter adapter(
            int port,
            WorkerDeliveryGatewayClient gateway
    ) {
        return adapter(
                port,
                gateway,
                Duration.ofMillis(10),
                1000
        );
    }

    private NettyWorkerDeliveryAdapter adapter(
            int port,
            WorkerDeliveryGatewayClient gateway,
            Duration reportSubmitInterval,
            int reportQueueCapacity
    ) {
        return (NettyWorkerDeliveryAdapter)
                NettyWorkerDeliveryAdapters.socket(
                "socket-1",
                gateway,
                "127.0.0.1",
                port,
                Duration.ofMillis(10),
                100,
                1000,
                reportSubmitInterval,
                reportQueueCapacity,
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

    private static void awaitRoutable(
            FakeGateway gateway,
            BufferedReader reader
    ) throws Exception {
        DeliveryCommand barrier = command();
        gateway.batches.add(Map.of(WORKER_ID, barrier));
        assertThat(new WorkerDeliveryCodec().decodeDeliveryCommand(
                reader.readLine()
        )).isEqualTo(barrier);
    }

    private static void awaitCommandConsumed(FakeGateway gateway)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (gateway.batches.isEmpty()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Command was not cached by the Adapter");
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
