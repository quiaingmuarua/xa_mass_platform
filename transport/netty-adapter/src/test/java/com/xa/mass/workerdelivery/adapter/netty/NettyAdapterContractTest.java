package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_CLOSE_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NettyAdapterContractTest {

    private static final String WORKER_ID = "server-issued-worker-id";
    private static final Duration WAIT = Duration.ofSeconds(3);

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final List<ScriptedHttpServer> httpServers =
            new CopyOnWriteArrayList<>();

    @AfterEach
    void closeHttpServers() {
        httpServers.forEach(ScriptedHttpServer::close);
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void deliversCommandAndReturnsTheOriginalResult(Protocol protocol)
            throws Exception {
        TestRemoteApi remoteApi = new TestRemoteApi();
        int port = availablePort();
        WorkerDeliveryAdapter adapter = adapter(
                protocol,
                port,
                remoteApi
        );
        adapter.start();

        try (WorkerPeer worker = connect(protocol, port)) {
            worker.send(identity());
            DeliveryCommand command = taskCommand("round-trip");
            remoteApi.commandBatches.add(Map.of(WORKER_ID, command));

            assertThat(codec.decodeDeliveryCommand(worker.receive()))
                    .isEqualTo(command);

            DeliveryReport report = DeliveryReport.fromCommand(
                    command,
                    WORKER,
                    WORKER_ID,
                    "200",
                    "{\"observed\":true}"
            );
            String encodedReport = codec.encodeDeliveryReport(report);
            worker.send(encodedReport);

            assertThat(remoteApi.resultAppended.await(
                    WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            )).isTrue();
            assertThat(remoteApi.appendedResults)
                    .containsExactly(List.of(encodedReport));
            assertThat(remoteApi.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);
        } finally {
            adapter.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void verifiedReconnectSkipsAnotherRemoteVerification(
            Protocol protocol
    ) throws Exception {
        TestRemoteApi remoteApi = new TestRemoteApi();
        int port = availablePort();
        WorkerDeliveryAdapter adapter = adapter(
                protocol,
                port,
                remoteApi
        );
        adapter.start();

        try {
            try (WorkerPeer first = connect(protocol, port)) {
                first.send(identity());
                DeliveryCommand barrier = taskCommand("first-route");
                remoteApi.commandBatches.add(Map.of(WORKER_ID, barrier));
                assertThat(codec.decodeDeliveryCommand(first.receive()))
                        .isEqualTo(barrier);
            }

            try (WorkerPeer reconnect = connect(protocol, port)) {
                reconnect.send(identity());
                DeliveryCommand command = taskCommand("reconnect-route");
                remoteApi.commandBatches.add(Map.of(WORKER_ID, command));
                assertThat(codec.decodeDeliveryCommand(reconnect.receive()))
                        .isEqualTo(command);
            }

            assertThat(remoteApi.verificationCount).hasValue(1);
            assertThat(remoteApi.verifiedWorkerIds)
                    .containsExactly(WORKER_ID);
        } finally {
            adapter.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void routeRejectionFlushesTerminalCommandBeforeClose(
            Protocol protocol
    ) throws Exception {
        TestRemoteApi remoteApi = new TestRemoteApi();
        remoteApi.verificationStatus = 409;
        int port = availablePort();
        WorkerDeliveryAdapter adapter = adapter(
                protocol,
                port,
                remoteApi
        );
        adapter.start();

        try (WorkerPeer worker = connect(protocol, port)) {
            worker.send(identity());

            DeliveryCommand close = codec.decodeDeliveryCommand(
                    worker.receive()
            );
            assertThat(close.src()).isEqualTo(ADAPTER);
            assertThat(close.dst()).isEqualTo(WORKER);
            assertThat(close.messageType())
                    .isEqualTo(WORKER_CONNECTION_CLOSE_EVENT_CODE);
            assertThat(close.payload()).isEqualTo("null");
            assertThat(close.forward()).isEmpty();
            assertThat(worker.awaitClosed()).isTrue();
            assertThat(remoteApi.appendedResults).isEmpty();
        } finally {
            adapter.close();
        }
    }

    private WorkerDeliveryAdapter adapter(
            Protocol protocol,
            int port,
            TestRemoteApi remoteApi
    ) {
        List<NettyAdapterProcessConfig> processes = List.of(
                new NettyAdapterProcessConfig.DeliveryCommand(
                        Duration.ofMillis(10),
                        100,
                        1000
                ),
                new NettyAdapterProcessConfig.DeliveryReport(
                        Duration.ofMillis(10),
                        1000
                )
        );
        return switch (protocol) {
            case WEBSOCKET -> NettyWorkerDeliveryAdapters.webSocket(
                    protocol.adapterId,
                    remoteApi.server.baseUri(),
                    Duration.ofSeconds(2),
                    "127.0.0.1",
                    port,
                    processes,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
            );
            case SOCKET -> NettyWorkerDeliveryAdapters.socket(
                    protocol.adapterId,
                    remoteApi.server.baseUri(),
                    Duration.ofSeconds(2),
                    "127.0.0.1",
                    port,
                    processes,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
            );
        };
    }

    private static WorkerPeer connect(
            Protocol protocol,
            int port
    ) throws Exception {
        return switch (protocol) {
            case WEBSOCKET -> WebSocketPeer.connect(port);
            case SOCKET -> SocketPeer.connect(port);
        };
    }

    private String identity() {
        return codec.encodeDeliveryReport(DeliveryReport.create(
                WORKER,
                WORKER_ID,
                ADAPTER,
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                "200",
                "null",
                ""
        ));
    }

    private static DeliveryCommand taskCommand(String marker) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "extension.worker.test.observe",
                System.currentTimeMillis() + 60_000,
                "{\"marker\":\"" + marker + "\"}",
                "test-forward"
        );
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not reserve an Adapter test port",
                    error
            );
        }
    }

    private enum Protocol {
        WEBSOCKET("contract-websocket"),
        SOCKET("contract-socket");

        private final String adapterId;

        Protocol(String adapterId) {
            this.adapterId = adapterId;
        }
    }

    private interface WorkerPeer extends AutoCloseable {

        void send(String message) throws Exception;

        String receive() throws Exception;

        boolean awaitClosed() throws Exception;

        @Override
        void close();
    }

    private static final class WebSocketPeer
            implements WorkerPeer, WebSocket.Listener {

        private final BlockingQueue<String> messages =
                new LinkedBlockingQueue<>();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder fragments = new StringBuilder();
        private volatile WebSocket socket;

        private static WebSocketPeer connect(int port) throws Exception {
            WebSocketPeer peer = new WebSocketPeer();
            peer.socket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(
                            URI.create(
                                    "ws://127.0.0.1:" + port
                                            + "/api/v1/worker-delivery/"
                                            + "websocket"
                            ),
                            peer
                    )
                    .get(2, TimeUnit.SECONDS);
            if (!peer.opened.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("WebSocket did not open");
            }
            return peer;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.countDown();
            webSocket.request(1);
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

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        @Override
        public void send(String message) throws Exception {
            socket.sendText(message, true).get(2, TimeUnit.SECONDS);
        }

        @Override
        public String receive() throws Exception {
            String message = messages.poll(
                    WAIT.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (message == null) {
                throw new AssertionError("Expected a WebSocket message");
            }
            return message;
        }

        @Override
        public boolean awaitClosed() throws InterruptedException {
            return closed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            WebSocket current = socket;
            if (current != null) {
                current.abort();
            }
        }
    }

    private static final class SocketPeer implements WorkerPeer {

        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private SocketPeer(Socket socket) throws IOException {
            this.socket = socket;
            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
            ));
            writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(),
                    StandardCharsets.UTF_8
            ));
        }

        private static SocketPeer connect(int port) throws IOException {
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout((int) WAIT.toMillis());
            return new SocketPeer(socket);
        }

        @Override
        public void send(String message) throws IOException {
            writer.write(message);
            writer.write('\n');
            writer.flush();
        }

        @Override
        public String receive() throws IOException {
            String message = reader.readLine();
            if (message == null) {
                throw new AssertionError("Expected a Socket message");
            }
            return message;
        }

        @Override
        public boolean awaitClosed() throws IOException {
            return reader.readLine() == null;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Test cleanup only.
            }
        }
    }

    private final class TestRemoteApi {

        private final ConcurrentLinkedQueue<Map<String, DeliveryCommand>>
                commandBatches = new ConcurrentLinkedQueue<>();
        private final List<List<String>> appendedResults =
                new CopyOnWriteArrayList<>();
        private final List<String> verifiedWorkerIds =
                new CopyOnWriteArrayList<>();
        private final AtomicInteger verificationCount = new AtomicInteger();
        private final CountDownLatch resultAppended = new CountDownLatch(1);
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );
        private volatile int verificationStatus = 204;

        private TestRemoteApi() {
            httpServers.add(server);
        }

        private Response handle(ScriptedHttpServer.Request request) {
            if (request.rawPath().endsWith("/commands:consume")) {
                return commandResponse(commandBatches.poll());
            }
            if (request.rawPath().endsWith("/results:append")) {
                @SuppressWarnings("unchecked")
                List<String> results = (List<String>) Jsons.parseObject(
                        request.body()
                ).get("results");
                appendedResults.add(List.copyOf(results));
                resultAppended.countDown();
                return new Response(202, Jsons.toJson(Map.of(
                        "acceptedCount",
                        results.size(),
                        "rejectedCount",
                        0
                )));
            }
            if (request.rawPath().endsWith(":verify-binding")) {
                verificationCount.incrementAndGet();
                verifiedWorkerIds.add(workerId(request.rawPath()));
                return new Response(
                        verificationStatus,
                        verificationStatus == 204 ? "" : "{}"
                );
            }
            return new Response(404, "{}");
        }

        private Response commandResponse(
                Map<String, DeliveryCommand> commands
        ) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            if (commands != null) {
                commands.forEach((target, command) -> encoded.put(
                        target,
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

        private String workerId(String path) {
            String marker = "/workers/";
            int start = path.indexOf(marker) + marker.length();
            int end = path.indexOf(":verify-binding", start);
            return java.net.URLDecoder.decode(
                    path.substring(start, end),
                    StandardCharsets.UTF_8
            );
        }
    }
}
