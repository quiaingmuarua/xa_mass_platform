package com.xa.mass.transport.android.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xa.mass.transport.client.TextWebSocketClient;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
public class AndroidOkHttpTextWebSocketClientTest {

    private final FakeConnector connector = new FakeConnector();
    private final RecordingListener listener = new RecordingListener();
    private final AtomicBoolean resourcesClosed =
            new AtomicBoolean();
    private AndroidOkHttpTextWebSocketClient client;

    @Before
    public void setUp() {
        client = new AndroidOkHttpTextWebSocketClient(
                new AndroidOkHttpTextWebSocketClient.NetworkResources(
                        connector,
                        () -> resourcesClosed.set(true)
                ),
                URI.create("ws://127.0.0.1:18084/worker"),
                Duration.ofSeconds(1),
                Duration.ofMillis(10)
        );
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Test
    public void serializesCallbacksOnDedicatedHandlerThread()
            throws Exception {
        client.start(listener);
        client.start(listener);
        FakeConnection connection = awaitConnection(0);

        connection.open();
        connection.text("command");
        connection.binary();

        await(() -> listener.events.size() == 3);
        assertEquals(
                List.of("open", "text:command", "binary"),
                listener.events
        );
        Set<String> callbackThreads = listener.callbackThreads.stream()
                .collect(Collectors.toSet());
        assertEquals(1, callbackThreads.size());
        assertTrue(
                callbackThreads.iterator().next()
                        .contains("xa-worker-websocket-client")
        );
        assertEquals(
                URI.create("ws://127.0.0.1:18084/worker"),
                connection.uri
        );
        assertTrue(client.send("result"));
        assertEquals(List.of("result"), connection.socket.sent);
        assertTrue(client.isConnected());
    }

    @Test
    public void reconnectsAndIgnoresSupersededConnectionCallbacks()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(client::isConnected);

        client.closeCurrent(1007, "invalid");
        await(() -> listener.disconnects.get() == 1);
        advanceReconnectClock();
        FakeConnection second = awaitConnection(1);
        second.open();
        await(client::isConnected);

        first.text("stale");
        second.text("current");
        await(() -> listener.events.contains("text:current"));

        assertFalse(listener.events.contains("text:stale"));
        assertEquals(2, listener.opens.get());
        assertEquals(1, listener.disconnects.get());
    }

    @Test
    public void rejectedSendReconnectsWithoutCachingMessage()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(client::isConnected);
        first.socket.rejectNextSend = true;

        assertFalse(client.send("result"));
        await(() -> listener.disconnects.get() == 1);
        advanceReconnectClock();
        FakeConnection second = awaitConnection(1);
        assertFalse(client.send("result"));
        second.open();
        await(client::isConnected);

        assertTrue(first.socket.sent.isEmpty());
        assertTrue(second.socket.sent.isEmpty());
    }

    @Test
    public void closeIsIdempotentAndSuppressesLaterCallbacks()
            throws Exception {
        client.start(listener);
        FakeConnection connection = awaitConnection(0);
        connection.open();
        await(client::isConnected);

        client.close();
        int eventCount = listener.events.size();
        connection.text("late");
        Thread.sleep(30);
        client.close();

        assertFalse(client.isConnected());
        assertEquals(eventCount, listener.events.size());
        assertTrue(connection.socket.cancelled);
        assertTrue(resourcesClosed.get());
    }

    private FakeConnection awaitConnection(int index) throws Exception {
        await(() -> connector.connections.size() > index);
        return connector.connections.get(index);
    }

    private static void advanceReconnectClock()
            throws InterruptedException {
        Thread.sleep(10);
        ShadowSystemClock.advanceBy(Duration.ofMillis(20));
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(3);
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
        private final AtomicInteger disconnects = new AtomicInteger();
        private final List<String> events =
                new CopyOnWriteArrayList<>();
        private final List<String> callbackThreads =
                new CopyOnWriteArrayList<>();

        @Override
        public void onOpen() {
            opens.incrementAndGet();
            record("open");
        }

        @Override
        public void onText(String message) {
            record("text:" + message);
        }

        @Override
        public void onBinary() {
            record("binary");
        }

        @Override
        public void onDisconnected() {
            disconnects.incrementAndGet();
            record("disconnected");
        }

        @Override
        public void onFailure(Throwable error) {
            record("failure");
        }

        private void record(String event) {
            events.add(event);
            callbackThreads.add(Thread.currentThread().getName());
        }
    }

    private static final class FakeConnector
            implements AndroidOkHttpTextWebSocketClient
            .WebSocketConnector {

        private final List<FakeConnection> connections =
                new CopyOnWriteArrayList<>();

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
            listener.onMessage(socket, ByteString.of((byte) 1));
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sent =
                new CopyOnWriteArrayList<>();
        private volatile boolean rejectNextSend;
        private volatile boolean cancelled;

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
