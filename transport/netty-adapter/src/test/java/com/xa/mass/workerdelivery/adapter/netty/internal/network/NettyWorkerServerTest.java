package com.xa.mass.workerdelivery.adapter.netty.internal.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NettyWorkerServerTest {

    @Test
    void socketServerOwnsLineFramingWritesAndPhysicalClose()
            throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        SocketNettyWorkerServer server = new SocketNettyWorkerServer(
                "socket-1",
                "127.0.0.1",
                port,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        server.start(handler);
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
            writer.write("inbound\n");
            writer.flush();
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.messages).containsExactly("inbound");
            assertThat(server.trackedConnectionCount()).isEqualTo(1);

            assertThat(server.writeText(handler.channel, "outbound"))
                    .isEqualTo(TextWriteAttempt.STARTED);
            assertThat(reader.readLine()).isEqualTo("outbound");

            server.writeTextAndClose(
                    handler.channel,
                    "terminal",
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            assertThat(reader.readLine()).isEqualTo("terminal");
            assertThat(reader.readLine()).isNull();
        } finally {
            server.close();
            server.close();
        }
        assertThat(server.trackedConnectionCount()).isZero();
    }

    @Test
    void webSocketServerOwnsHandshakeFramesWritesAndCloseMapping()
            throws Exception {
        int port = availablePort();
        RecordingStringHandler handler = new RecordingStringHandler();
        WebSocketNettyWorkerServer server = new WebSocketNettyWorkerServer(
                "websocket-1",
                "127.0.0.1",
                port,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        server.start(handler);
        WebSocketProbe probe = new WebSocketProbe();
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + port
                                        + "/api/v1/worker-delivery/websocket"
                        ),
                        probe
                ).join();
        try {
            socket.sendText("inbound", true).join();
            assertThat(handler.received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.messages).containsExactly("inbound");
            assertThat(server.trackedConnectionCount()).isEqualTo(1);

            assertThat(server.writeText(handler.channel, "outbound"))
                    .isEqualTo(TextWriteAttempt.STARTED);
            assertThat(probe.awaitMessage()).isEqualTo("outbound");

            server.writeTextAndClose(
                    handler.channel,
                    "terminal",
                    AdapterConnectionCloseReason.TRANSPORT_ERROR
            );
            assertThat(probe.awaitMessage()).isEqualTo("terminal");
            assertThat(probe.closed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            socket.abort();
            server.close();
            server.close();
        }
        assertThat(server.trackedConnectionCount()).isZero();
    }

    @Test
    void bothPhysicalServersRejectNonSharableMechanismsBeforeBinding()
            throws Exception {
        NettyWorkerServer websocket = new WebSocketNettyWorkerServer(
                "websocket-1",
                "127.0.0.1",
                availablePort(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        NettyWorkerServer socket = new SocketNettyWorkerServer(
                "socket-1",
                "127.0.0.1",
                availablePort(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
        ChannelHandler nonSharable = new ChannelInboundHandlerAdapter();
        try {
            assertThatThrownBy(() -> websocket.start(nonSharable))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@Sharable");
            assertThatThrownBy(() -> socket.start(nonSharable))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("@Sharable");
        } finally {
            websocket.close();
            socket.close();
        }
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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

    private static final class WebSocketProbe implements WebSocket.Listener {

        private final LinkedBlockingQueue<String> messages =
                new LinkedBlockingQueue<>();
        private final CountDownLatch closed = new CountDownLatch(1);

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
            closed.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }

        private String awaitMessage() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }
    }
}
