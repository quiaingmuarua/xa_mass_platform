package com.xa.mass.transport.client.okhttp;

import com.xa.mass.transport.client.TextWebSocketClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OkHttpTextWebSocketClientTest {

    private final FakeConnector connector = new FakeConnector();
    private final RecordingListener listener = new RecordingListener();
    private OkHttpTextWebSocketClient client;

    @BeforeEach
    void setUp() {
        client = new OkHttpTextWebSocketClient(
                new OkHttpTextWebSocketClient.ConnectorResources(
                        connector,
                        () -> connector.closed = true
                ),
                URI.create(
                        "ws://127.0.0.1:18083/api/v1/"
                                + "worker-delivery/websocket"
                ),
                Duration.ofMillis(5)
        );
        client.start(listener);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void forwardsOnlyRawNetworkEventsAndSendsText() {
        FakeConnection first = connector.connections.get(0);
        first.open();
        first.text("command");
        first.binary();

        assertTrue(client.isConnected());
        assertTrue(client.send("result"));
        assertEquals(List.of("result"), first.socket.sent);
        assertEquals(1, listener.opens.get());
        assertEquals(List.of("command"), listener.texts);
        assertEquals(1, listener.binaries.get());
        assertEquals(
                URI.create(
                        "ws://127.0.0.1:18083/api/v1/"
                                + "worker-delivery/websocket"
                ),
                first.uri
        );
    }

    @Test
    void disconnectReconnectsAndSuppressesOldCallbacks()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();

        client.closeCurrent(1007, "invalid");
        await(() -> connector.connections.size() == 2);
        FakeConnection second = connector.connections.get(1);
        second.open();
        first.text("stale");
        second.text("current");

        assertEquals(List.of("current"), listener.texts);
        assertEquals(2, listener.opens.get());
        assertEquals(1, listener.disconnects.get());
        assertTrue(client.isConnected());
    }

    @Test
    void rejectedSendDisconnectsAndSchedulesReconnect()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        first.socket.rejectNextSend = true;

        assertFalse(client.send("result"));
        await(() -> connector.connections.size() == 2);

        assertTrue(first.socket.cancelled);
        assertEquals(1, listener.disconnects.get());
    }

    @Test
    void closeIsIdempotentAndClosesOwnedResources() {
        FakeConnection first = connector.connections.get(0);
        first.open();

        client.close();
        client.close();

        assertFalse(client.isConnected());
        assertTrue(first.socket.cancelled);
        assertTrue(connector.closed);
    }

    @Test
    void publicConstructorRequiresFinalWebSocketUri() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OkHttpTextWebSocketClient(
                        URI.create("http://127.0.0.1:18083"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                )
        );
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(2);
        while (!check.value() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(check.value());
    }

    @FunctionalInterface
    private interface Check {

        boolean value();
    }

    private static final class RecordingListener
            implements TextWebSocketClient.Listener {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger binaries = new AtomicInteger();
        private final AtomicInteger disconnects = new AtomicInteger();
        private final List<String> texts = new ArrayList<>();

        @Override
        public void onOpen() {
            opens.incrementAndGet();
        }

        @Override
        public void onText(String message) {
            texts.add(message);
        }

        @Override
        public void onBinary() {
            binaries.incrementAndGet();
        }

        @Override
        public void onDisconnected() {
            disconnects.incrementAndGet();
        }

        @Override
        public void onFailure(Throwable error) {
        }
    }

    private static final class FakeConnector
            implements OkHttpTextWebSocketClient.WebSocketConnector {

        private final List<FakeConnection> connections =
                new ArrayList<>();
        private boolean closed;

        @Override
        public WebSocket connect(
                URI uri,
                WebSocketListener listener
        ) {
            FakeConnection connection =
                    new FakeConnection(uri, listener);
            connections.add(connection);
            return connection.socket;
        }
    }

    private static final class FakeConnection {

        private final URI uri;
        private final WebSocketListener listener;
        private final FakeWebSocket socket = new FakeWebSocket();

        private FakeConnection(
                URI uri,
                WebSocketListener listener
        ) {
            this.uri = uri;
            this.listener = listener;
        }

        private void open() {
            listener.onOpen(socket, null);
        }

        private void text(String message) {
            listener.onMessage(socket, message);
        }

        private void binary() {
            listener.onMessage(
                    socket,
                    ByteString.of((byte) 1)
            );
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sent = new ArrayList<>();
        private boolean rejectNextSend;
        private boolean cancelled;

        @Override
        public Request request() {
            return new Request.Builder()
                    .url("http://127.0.0.1/")
                    .build();
        }

        @Override
        public long queueSize() {
            return 0;
        }

        @Override
        public boolean send(String text) {
            if (rejectNextSend) {
                rejectNextSend = false;
                return false;
            }
            sent.add(text);
            return true;
        }

        @Override
        public boolean send(ByteString bytes) {
            return false;
        }

        @Override
        public boolean close(int code, String reason) {
            return true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
