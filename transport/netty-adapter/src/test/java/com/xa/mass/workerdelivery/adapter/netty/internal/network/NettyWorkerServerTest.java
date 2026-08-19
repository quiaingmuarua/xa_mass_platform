package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NettyWorkerServerTest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void physicalServerContractNormalizesTextAndFlushesBeforeClose(
            Protocol protocol
    ) throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        NettyWorkerServer server = protocol.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(handler);
        try (Peer peer = protocol.connect(port)) {
            peer.send("inbound");
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.messages).containsExactly("inbound");

            EmbeddedChannel foreign = new EmbeddedChannel();
            try {
                assertThat(server.writeText(foreign, "foreign"))
                        .isEqualTo(TextWriteAttempt.RETRY_LATER);
            } finally {
                foreign.finishAndReleaseAll();
            }

            assertThat(server.writeText(handler.channel, "outbound"))
                    .isEqualTo(TextWriteAttempt.STARTED);
            assertThat(peer.awaitMessage()).isEqualTo("outbound");

            server.writeTextAndClose(
                    handler.channel,
                    "terminal",
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            assertThat(peer.awaitMessage()).isEqualTo("terminal");
            assertThat(peer.awaitClosed()).isTrue();
            assertThat(server.writeText(handler.channel, "late"))
                    .isEqualTo(TextWriteAttempt.RETRY_LATER);
        } finally {
            server.close();
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void physicalServerRejectsNonSharableHandlerBeforeBinding(
            Protocol protocol
    ) throws Exception {
        int port = availablePort();
        NettyWorkerServer server = protocol.server(
                port,
                DEFAULT_TIMEOUT
        );
        try {
            assertThatThrownBy(() -> server.start(
                    new ChannelInboundHandlerAdapter()
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@Sharable");
            try (ServerSocket claimed = new ServerSocket(port)) {
                assertThat(claimed.isBound()).isTrue();
            }
        } finally {
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void physicalServerRejectsRepeatedStartAndClosesIdempotently(
            Protocol protocol
    ) throws Exception {
        NettyWorkerServer server = protocol.server(
                availablePort(),
                DEFAULT_TIMEOUT
        );
        server.start(new NoopStringHandler());
        assertThatThrownBy(() -> server.start(new NoopStringHandler()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be started again");

        server.close();
        server.close();
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void bindFailureRollsBackPhysicalResources(Protocol protocol)
            throws Exception {
        int port = availablePort();
        NettyWorkerServer failed = protocol.server(
                port,
                DEFAULT_TIMEOUT
        );
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.setReuseAddress(false);
            blocker.bind(new InetSocketAddress("127.0.0.1", port));
            assertThatThrownBy(() -> failed.start(
                    new NoopStringHandler()
            ))
                    .isInstanceOfSatisfying(
                            WorkerDeliveryAdapterException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .LISTENER_START_FAILED
                            )
                    );
        } finally {
            failed.close();
        }

        NettyWorkerServer replacement = protocol.server(
                port,
                DEFAULT_TIMEOUT
        );
        try {
            replacement.start(new NoopStringHandler());
        } finally {
            replacement.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void serverCloseTerminatesEveryAcceptedPhysicalConnection(
            Protocol protocol
    ) throws Exception {
        int port = availablePort();
        ActiveConnectionHandler handler = new ActiveConnectionHandler(2);
        NettyWorkerServer server = protocol.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(handler);
        try (Socket first = new Socket("127.0.0.1", port);
                Socket second = new Socket("127.0.0.1", port)) {
            first.setSoTimeout(2_000);
            second.setSoTimeout(2_000);
            assertThat(handler.active.await(2, TimeUnit.SECONDS)).isTrue();

            server.close();

            assertThat(awaitEof(first)).isTrue();
            assertThat(awaitEof(second)).isTrue();
        } finally {
            server.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Protocol.class)
    void ownerShutdownBudgetCannotBecomeAnUnboundedWait(
            Protocol protocol
    ) throws Exception {
        int port = availablePort();
        StallingCloseHandler handler = new StallingCloseHandler();
        NettyWorkerServer server = protocol.server(
                port,
                Duration.ofMillis(40)
        );
        server.start(handler);
        try (Socket peer = new Socket("127.0.0.1", port)) {
            assertThat(handler.active.await(2, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();

            assertThatThrownBy(server::close)
                    .isInstanceOfSatisfying(
                            WorkerDeliveryAdapterException.class,
                            error -> {
                                assertThat(error.errorCode()).isEqualTo(
                                        WorkerDeliveryAdapterErrorCode
                                                .SHUTDOWN_TIMEOUT
                                );
                                assertThat(error.operation())
                                        .startsWith("netty.");
                            }
                    );

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            handler.release();
            server.close();
        }
    }

    @Test
    void webSocketOwnsPathBinaryRejectionAndCloseCode() throws Exception {
        int port = availablePort();
        NettyWorkerServer server = Protocol.WEBSOCKET.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(new NoopStringHandler());
        WebSocketProbe probe = new WebSocketProbe();
        WebSocket socket = openWebSocket(port, probe);
        try {
            socket.sendBinary(ByteBuffer.wrap(new byte[]{1}), true).join();
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closeStatusCode).isEqualTo(1003);

            assertThatThrownBy(() -> HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(
                            URI.create("ws://127.0.0.1:" + port + "/wrong"),
                            new WebSocketProbe()
                    ).join())
                    .hasCauseInstanceOf(
                            java.net.http.WebSocketHandshakeException.class
                    );
        } finally {
            socket.abort();
            server.close();
        }
    }

    @Test
    void webSocketManagementCloseUsesNormalClosure() throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        NettyWorkerServer server = Protocol.WEBSOCKET.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(handler);
        WebSocketProbe probe = new WebSocketProbe();
        WebSocket socket = openWebSocket(port, probe);
        try {
            socket.sendText("identify", true).join();
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();

            server.closeConnection(
                    handler.channel,
                    AdapterConnectionCloseReason.MANAGEMENT_REQUEST
            );

            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.closeStatusCode).isEqualTo(1000);
        } finally {
            socket.abort();
            server.close();
        }
    }

    @Test
    void socketManagementCloseUsesTcpClose() throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        NettyWorkerServer server = Protocol.SOCKET.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(handler);
        try (SocketPeer peer = new SocketPeer(
                new Socket("127.0.0.1", port)
        )) {
            peer.send("identify");
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();

            server.closeConnection(
                    handler.channel,
                    AdapterConnectionCloseReason.MANAGEMENT_REQUEST
            );

            assertThat(peer.awaitClosed()).isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void socketOwnsCrLfInputAndAlwaysWritesLf() throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        NettyWorkerServer server = Protocol.SOCKET.server(
                port,
                DEFAULT_TIMEOUT
        );
        server.start(handler);
        try (Socket socket = new Socket("127.0.0.1", port);
                BufferedReader reader = reader(socket);
                BufferedWriter writer = writer(socket)) {
            socket.setSoTimeout(2_000);
            writer.write("inbound\r\n");
            writer.flush();
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.messages).containsExactly("inbound");

            assertThat(server.writeText(handler.channel, "outbound"))
                    .isEqualTo(TextWriteAttempt.STARTED);
            assertThat(reader.readLine()).isEqualTo("outbound");
        } finally {
            server.close();
        }
    }

    private enum Protocol {
        WEBSOCKET {
            @Override
            NettyWorkerServer server(int port, Duration shutdownTimeout) {
                return new WebSocketNettyWorkerServer(
                        "websocket-test",
                        "127.0.0.1",
                        port,
                        Duration.ofSeconds(1),
                        shutdownTimeout
                );
            }

            @Override
            Peer connect(int port) {
                WebSocketProbe probe = new WebSocketProbe();
                return new WebSocketPeer(openWebSocket(port, probe), probe);
            }
        },
        SOCKET {
            @Override
            NettyWorkerServer server(int port, Duration shutdownTimeout) {
                return new SocketNettyWorkerServer(
                        "socket-test",
                        "127.0.0.1",
                        port,
                        Duration.ofSeconds(1),
                        shutdownTimeout
                );
            }

            @Override
            Peer connect(int port) throws Exception {
                return new SocketPeer(new Socket("127.0.0.1", port));
            }
        };

        abstract NettyWorkerServer server(
                int port,
                Duration shutdownTimeout
        );

        abstract Peer connect(int port) throws Exception;
    }

    private interface Peer extends AutoCloseable {

        void send(String message) throws Exception;

        String awaitMessage() throws Exception;

        boolean awaitClosed() throws Exception;

        @Override
        void close() throws Exception;
    }

    private static final class SocketPeer implements Peer {

        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private SocketPeer(Socket socket) throws Exception {
            this.socket = socket;
            socket.setSoTimeout(2_000);
            reader = reader(socket);
            writer = writer(socket);
        }

        @Override
        public void send(String message) throws Exception {
            writer.write(message);
            writer.write('\n');
            writer.flush();
        }

        @Override
        public String awaitMessage() throws Exception {
            return reader.readLine();
        }

        @Override
        public boolean awaitClosed() throws Exception {
            return reader.readLine() == null;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    private static final class WebSocketPeer implements Peer {

        private final WebSocket socket;
        private final WebSocketProbe probe;

        private WebSocketPeer(WebSocket socket, WebSocketProbe probe) {
            this.socket = socket;
            this.probe = probe;
        }

        @Override
        public void send(String message) {
            socket.sendText(message, true).join();
        }

        @Override
        public String awaitMessage() throws Exception {
            return probe.messages.poll(2, TimeUnit.SECONDS);
        }

        @Override
        public boolean awaitClosed() throws Exception {
            return probe.closed.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    @ChannelHandler.Sharable
    private static final class RecordingStringHandler
            extends SimpleChannelInboundHandler<String> {

        private final CountDownLatch received = new CountDownLatch(1);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile Channel channel;

        @Override
        protected void channelRead0(
                ChannelHandlerContext context,
                String message
        ) {
            channel = context.channel();
            messages.add(message);
            received.countDown();
        }
    }

    @ChannelHandler.Sharable
    private static final class NoopStringHandler
            extends SimpleChannelInboundHandler<String> {

        @Override
        protected void channelRead0(
                ChannelHandlerContext context,
                String message
        ) {
        }
    }

    @ChannelHandler.Sharable
    private static final class ActiveConnectionHandler
            extends ChannelInboundHandlerAdapter {

        private final CountDownLatch active;

        private ActiveConnectionHandler(int expectedConnections) {
            active = new CountDownLatch(expectedConnections);
        }

        @Override
        public void channelActive(ChannelHandlerContext context) {
            active.countDown();
            context.fireChannelActive();
        }
    }

    @ChannelHandler.Sharable
    private static final class StallingCloseHandler
            extends ChannelDuplexHandler {

        private final CountDownLatch active = new CountDownLatch(1);
        private final AtomicBoolean stalling = new AtomicBoolean(true);
        private volatile ChannelHandlerContext context;

        @Override
        public void channelActive(ChannelHandlerContext context) {
            this.context = context;
            active.countDown();
            context.fireChannelActive();
        }

        @Override
        public void close(
                ChannelHandlerContext context,
                ChannelPromise promise
        ) {
            if (!stalling.get()) {
                context.close(promise);
            }
        }

        private void release() {
            stalling.set(false);
            ChannelHandlerContext current = context;
            if (current != null) {
                try {
                    current.executor().execute(current::close);
                } catch (java.util.concurrent.RejectedExecutionException
                        ignored) {
                    // The bounded shutdown may already have terminated it.
                }
            }
        }
    }

    private static final class WebSocketProbe implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages =
                new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile int closeStatusCode;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            messages.add(data.toString());
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

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }
    }

    private static WebSocket openWebSocket(int port, WebSocketProbe probe) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + port
                                        + "/api/v1/worker-delivery/websocket"
                        ),
                        probe
                ).join();
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(
                socket.getInputStream(),
                StandardCharsets.UTF_8
        ));
    }

    private static BufferedWriter writer(Socket socket) throws Exception {
        return new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(),
                StandardCharsets.UTF_8
        ));
    }

    private static boolean awaitEof(Socket socket) throws Exception {
        while (socket.getInputStream().read() != -1) {
            // WebSocket close frames may precede the physical EOF.
        }
        return true;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
