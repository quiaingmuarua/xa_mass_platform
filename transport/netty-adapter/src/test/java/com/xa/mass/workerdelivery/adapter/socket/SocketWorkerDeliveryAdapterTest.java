package com.xa.mass.workerdelivery.adapter.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_COMMAND;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.TASK_ITEM_RESULT;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType.WORKER_BIND;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
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

            WorkerCommandEnvelope command = command();
            gateway.batches.add(Map.of("worker-1", command));
            String commandLine = reader.readLine();
            WorkerConnectionMessage commandMessage =
                    codec.decodeWorkerConnectionMessage(commandLine);
            assertThat(commandMessage.messageType())
                    .isEqualTo(TASK_ITEM_COMMAND.name());
            assertThat(codec.decodeWorkerCommand(
                    commandMessage.payload()
            )).isEqualTo(command);

            SeedResult result = new SeedResult(
                    command.commandId(),
                    "context",
                    "200",
                    "null"
            );
            String encodedResult = codec.encodeSeedResult(result);
            writer.write(codec.encodeWorkerConnectionMessage(
                    new WorkerConnectionMessage(
                            TASK_ITEM_RESULT.name(),
                            encodedResult
                    )
            ));
            writer.write('\n');
            writer.flush();

            assertThat(gateway.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(gateway.appendedResults)
                    .containsExactly(List.of(encodedResult));
            assertThat(gateway.appendedSources)
                    .containsExactly(WORKER);
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
            assertClosedAfterLine(port, "{\"workerId\":\"worker-1\"}");
            assertClosedAfterLine(
                    port,
                    codec.encodeWorkerConnectionMessage(
                            new WorkerConnectionMessage(
                                    TASK_ITEM_RESULT.name(),
                                    codec.encodeSeedResult(new SeedResult(
                                            "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                                            "context",
                                            "200",
                                            "null"
                                    ))
                            )
                    )
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
        return codec.encodeWorkerConnectionMessage(
                new WorkerConnectionMessage(
                        WORKER_BIND.name(),
                        codec.encodeWorkerConnectionBind(
                                new WorkerConnectionBind(workerId)
                        )
                )
        );
    }

    private static WorkerCommandEnvelope command() {
        return new WorkerCommandEnvelope(
                "a5e9e10d-f78b-469e-93ab-864b49c189c1",
                WorkerMessageType.TASK_ITEM,
                System.currentTimeMillis() + 60_000,
                "opaque"
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
                Map<String, WorkerCommandEnvelope>
                > batches =
                new ConcurrentLinkedQueue<>();
        private final List<String> endpointManagerIds =
                new CopyOnWriteArrayList<>();
        private final List<SeedResultSource> appendedSources =
                new CopyOnWriteArrayList<>();
        private final List<List<String>> appendedResults =
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
                SeedResultSource source,
                List<String> results
        ) {
            endpointManagerIds.add(endpointManagerId);
            appendedSources.add(source);
            appendedResults.add(List.copyOf(results));
            resultAppended.countDown();
        }
    }
}
