package com.xa.mass.transport.client.okhttp;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void setUp() throws Exception {
        client = new OkHttpTextWebSocketClient(
                new OkHttpTextWebSocketClient.ConnectorResources(
                        connector,
                        () -> connector.closed = true
                ),
                URI.create(
                        "ws://127.0.0.1:18083/api/v1/"
                                + "worker-delivery/websocket"
                ),
                reconnectPolicy()
        );
        client.start(listener);
        await(() -> connector.connections.size() == 1);
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void forwardsOnlyTextMessagesAndSendsText() throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(client::isConnected);
        first.text("command");
        await(() -> listener.messages.size() == 1);

        assertTrue(client.isConnected());
        assertTrue(client.send("result"));
        assertEquals(List.of("result"), first.socket.sent);
        assertEquals(1, listener.opens.get());
        assertEquals(List.of("command"), listener.messages);
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

        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
        await(() -> connector.connections.size() == 2);
        FakeConnection second = connector.connections.get(1);
        second.open();
        first.text("stale");
        second.text("current");

        await(() -> listener.messages.size() == 1);
        assertEquals(List.of("current"), listener.messages);
        assertEquals(2, listener.opens.get());
        assertEquals(1, listener.disconnects.get());
        assertTrue(client.isConnected());
        assertEquals(1007, first.socket.closeCode);
    }

    @Test
    void rejectedSendIsClosedByTheTransportBoundaryAndReconnects()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(client::isConnected);
        first.socket.rejectNextSend = true;

        assertFalse(client.send("result"));
        client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        await(() -> connector.connections.size() == 2);

        assertEquals(1011, first.socket.closeCode);
        assertEquals(1, listener.disconnects.get());
    }

    @Test
    void binaryFrameIsRejectedInsideTheWebSocketClient()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(client::isConnected);

        first.binary();
        await(() -> connector.connections.size() == 2);

        assertEquals(1003, first.socket.closeCode);
        assertTrue(listener.messages.isEmpty());
    }

    @Test
    void listenerCallbacksAreSerialized() throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        first.text("one");
        first.text("two");
        await(() -> listener.messages.size() == 2);

        assertEquals(1, listener.callbackThreads.stream().distinct().count());
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
                        reconnectPolicy()
                )
        );
    }

    @Test
    void exhaustsAfterBoundedUnstableAttempts() throws Exception {
        connector.connections.get(0).fail();
        await(() -> connector.connections.size() == 2);
        connector.connections.get(1).fail();
        await(() -> connector.connections.size() == 3);
        connector.connections.get(2).fail();

        await(() -> listener.exhausted.get() == 1);
        Thread.sleep(30);
        assertEquals(3, connector.connections.size());
        assertEquals(1, listener.exhausted.get());
    }

    @Test
    void stableConnectionResetsTheUnstableAttemptCount()
            throws Exception {
        connector.connections.get(0).fail();
        await(() -> connector.connections.size() == 2);
        FakeConnection stable = connector.connections.get(1);
        stable.open();
        Thread.sleep(70);
        client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
        await(() -> connector.connections.size() == 3);

        connector.connections.get(2).fail();
        await(() -> connector.connections.size() == 4);
        connector.connections.get(3).fail();

        await(() -> listener.exhausted.get() == 1);
        assertEquals(4, connector.connections.size());
    }

    private static TextMessageReconnectPolicy reconnectPolicy() {
        return TextMessageReconnectPolicy.of(
                3,
                Duration.ofMillis(5),
                Duration.ofMillis(40)
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
            implements TextMessageClient.Listener {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger disconnects = new AtomicInteger();
        private final AtomicInteger exhausted = new AtomicInteger();
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final List<String> callbackThreads =
                new CopyOnWriteArrayList<>();

        @Override
        public void onOpen() {
            callbackThreads.add(Thread.currentThread().getName());
            opens.incrementAndGet();
        }

        @Override
        public void onMessage(String message) {
            callbackThreads.add(Thread.currentThread().getName());
            messages.add(message);
        }

        @Override
        public void onDisconnected() {
            callbackThreads.add(Thread.currentThread().getName());
            disconnects.incrementAndGet();
        }

        @Override
        public void onFailure(Throwable error) {
            callbackThreads.add(Thread.currentThread().getName());
        }

        @Override
        public void onReconnectExhausted() {
            callbackThreads.add(Thread.currentThread().getName());
            exhausted.incrementAndGet();
        }
    }

    private static final class FakeConnector
            implements OkHttpTextWebSocketClient.WebSocketConnector {

        private final List<FakeConnection> connections =
                new CopyOnWriteArrayList<>();
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

        private void fail() {
            listener.onFailure(
                    socket,
                    new IllegalStateException("connection failed"),
                    null
            );
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sent = new ArrayList<>();
        private boolean rejectNextSend;
        private boolean cancelled;
        private int closeCode = -1;

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
            closeCode = code;
            return true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
