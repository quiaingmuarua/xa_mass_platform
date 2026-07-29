package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SocketWorkerDeliveryAdapterTest {

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void bindsWithCrLfAndCompletesCommandResultRound()
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
            writer.write(bind("worker-1"));
            writer.write("\r\n");
            writer.flush();
            awaitBound(adapter);

            WorkerCommand command = command();
            gateway.batches.add(Map.of("worker-1", command));
            String commandLine = reader.readLine();
            assertThat(codec.decodeWorkerCommand(commandLine))
                    .isEqualTo(command);

            WorkerResult result = new WorkerResult(
                    command.messageId(),
                    TASK,
                    command.messageType(),
                    "200",
                    "null",
                    command.forward()
            );
            String encodedResult = codec.encodeWorkerResult(result);
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
    void invalidOrRepeatedBindingClosesTheConnection() throws Exception {
        int port = availablePort();
        SocketWorkerDeliveryAdapter adapter = adapter(
                port,
                new FakeGateway()
        );
        adapter.start();
        try {
            assertClosedAfterLine(
                    port,
                    "{\"extra\":true,\"workerId\":\"worker-1\"}"
            );
            assertClosedAfterLine(
                    port,
                    codec.encodeWorkerResult(new WorkerResult(
                            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
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
                String encodedBind = bind("worker-1");
                writer.write(encodedBind);
                writer.write('\n');
                writer.write(encodedBind);
                writer.write('\n');
                writer.flush();
                assertThat(reader.readLine()).isNull();
            }
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

    private String bind(String workerId) {
        return codec.encodeWorkerConnectionBind(
                new WorkerConnectionBind(workerId)
        );
    }

    private static WorkerCommand command() {
        return new WorkerCommand(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                TASK,
                WORKER,
                "test.observe",
                System.currentTimeMillis() + 60_000,
                "{}",
                "context"
        );
    }

    private static void awaitBound(SocketWorkerDeliveryAdapter adapter)
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
    }
}
