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
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SocketAdapterContractTest {

    private static final String WORKER_ID = "server-issued-worker-id";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final List<ScriptedHttpServer> httpServers =
            new CopyOnWriteArrayList<>();

    @AfterEach
    void closeHttpServers() {
        httpServers.forEach(ScriptedHttpServer::close);
    }

    @Test
    void closeTerminatesAnUnboundSocketChannel() throws Exception {
        int port = availablePort();
        NettyWorkerDeliveryAdapter adapter = adapter(
                port,
                new TestRemoteApi()
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
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(port, remoteApi);
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
            remoteApi.batches.add(Map.of(WORKER_ID, command));
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

            assertThat(remoteApi.resultAppended.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(remoteApi.appendedResults)
                    .containsExactly(List.of(encodedResult));
            assertThat(remoteApi.endpointManagerIds)
                    .containsOnly("socket-1");
        } finally {
            manager.close();
        }
    }

    @Test
    void reconnectReusesVerifiedRouteAndReceivesCachedCommand()
            throws Exception {
        int port = availablePort();
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(port, remoteApi);
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
                awaitRoutable(remoteApi, reader);
                assertThat(remoteApi.verifiedWorkerIds)
                        .containsExactly(WORKER_ID);
                first.shutdownOutput();
                assertThat(reader.readLine()).isNull();
            }

            DeliveryCommand command = command();
            remoteApi.batches.add(Map.of(WORKER_ID, command));
            awaitCommandConsumed(remoteApi);

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

                assertThat(remoteApi.verifiedWorkerIds)
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
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                port,
                remoteApi
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
                awaitRoutable(remoteApi, reader);

                writer.write(identity);
                writer.write('\n');
                writer.write("{bad-json\n");
                writer.write(codec.encodeDeliveryReport(result(
                        TASK,
                        "test.observe",
                        "23002",
                        "context"
                )) + "\n");
                String systemAccepted = codec.encodeDeliveryReport(result(
                        SYSTEM,
                        "system.observe",
                        "200",
                        ""
                ));
                writer.write(systemAccepted + "\n");
                writer.write(codec.encodeDeliveryReport(result(
                        ADAPTER,
                        "extension.adapter.unknown",
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

                assertThat(remoteApi.resultAppended.await(
                        2,
                        TimeUnit.SECONDS
                )).isTrue();
                awaitResultCount(remoteApi, 2);
                assertThat(remoteApi.appendedResults.stream()
                        .flatMap(List::stream)
                        .toList())
                        .containsExactly(systemAccepted, accepted);
                assertThat(remoteApi.verifiedWorkerIds)
                        .containsExactly(WORKER_ID);
            }
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
        NettyWorkerDeliveryAdapter adapter = adapter(port, remoteApi);
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
            assertThat(remoteApi.appendedResults).isEmpty();
        } finally {
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
        NettyWorkerDeliveryAdapter adapter = adapter(port, remoteApi);
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
        TestRemoteApi remoteApi = new TestRemoteApi();
        NettyWorkerDeliveryAdapter adapter = adapter(
                port,
                remoteApi,
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
            awaitRoutable(remoteApi, reader);

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
            assertThat(remoteApi.appendedResults).isEmpty();
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
            TestRemoteApi remoteApi
    ) {
        return adapter(
                port,
                remoteApi,
                Duration.ofMillis(10),
                1000
        );
    }

    private NettyWorkerDeliveryAdapter adapter(
            int port,
            TestRemoteApi remoteApi,
            Duration reportSubmitInterval,
            int reportQueueCapacity
    ) {
        return (NettyWorkerDeliveryAdapter)
                NettyWorkerDeliveryAdapters.socket(
                "socket-1",
                remoteApi.server.baseUri(),
                Duration.ofSeconds(2),
                "127.0.0.1",
                port,
                List.of(
                    new NettyAdapterProcessConfig.DeliveryCommand(
                            Duration.ofMillis(10),
                            100,
                            1000
                    ),
                    new NettyAdapterProcessConfig.DeliveryReport(
                            reportSubmitInterval,
                            reportQueueCapacity
                    )
                ),
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
            TestRemoteApi remoteApi,
            BufferedReader reader
    ) throws Exception {
        DeliveryCommand barrier = command();
        remoteApi.batches.add(Map.of(WORKER_ID, barrier));
        assertThat(new WorkerDeliveryCodec().decodeDeliveryCommand(
                reader.readLine()
        )).isEqualTo(barrier);
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

    private static void awaitResultCount(TestRemoteApi remoteApi, int count)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            long observed = remoteApi.appendedResults.stream()
                    .mapToLong(List::size)
                    .sum();
            if (observed >= count) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Expected Worker results were not appended");
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
                            Jsons.parseObject(codec.encodeDeliveryCommand(
                                    command
                            ))
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
                    StandardCharsets.UTF_8
            );
        }

        private String workerId(String path) {
            String marker = "/workers/";
            int start = path.indexOf(marker) + marker.length();
            int end = path.indexOf(":verify-binding", start);
            return URLDecoder.decode(
                    path.substring(start, end),
                    StandardCharsets.UTF_8
            );
        }
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
