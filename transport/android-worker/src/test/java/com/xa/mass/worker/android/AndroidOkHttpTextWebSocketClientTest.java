package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

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
                reconnectPolicy()
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

        await(() -> listener.events.size() == 2);
        assertEquals(
                List.of("open", "message:command"),
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
    }

    @Test
    public void reconnectsAndIgnoresSupersededConnectionCallbacks()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(() -> listener.opens.get() == 1);

        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
        await(() -> first.socket.closeCode == 1007);
        advanceReconnectClock();
        FakeConnection second = awaitConnection(1);
        second.open();
        await(() -> listener.opens.get() == 2);

        first.text("stale");
        second.text("current");
        await(() -> listener.events.contains("message:current"));

        assertFalse(listener.events.contains("message:stale"));
        assertEquals(2, listener.opens.get());
        assertEquals(0, listener.terminations.get());
    }

    @Test
    public void rejectedSendReconnectsWithoutCachingMessage()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(() -> listener.opens.get() == 1);
        first.socket.rejectNextSend = true;

        assertFalse(client.send("result"));
        client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        await(() -> first.socket.closeCode == 1011);
        advanceReconnectClock();
        FakeConnection second = awaitConnection(1);
        assertFalse(client.send("result"));
        second.open();
        await(() -> listener.opens.get() == 2);

        assertTrue(first.socket.sent.isEmpty());
        assertTrue(second.socket.sent.isEmpty());
        assertEquals(1011, first.socket.closeCode);
    }

    @Test
    public void binaryFrameIsRejectedInsideTheWebSocketClient()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(() -> listener.opens.get() == 1);

        first.binary();
        await(() -> first.socket.closeCode == 1003);
        advanceReconnectClock();
        awaitConnection(1);

        assertEquals(1003, first.socket.closeCode);
        assertFalse(listener.events.contains("binary"));
    }

    @Test
    public void failureReconnectsWithoutRuntimeCallback()
            throws Exception {
        client.start(listener);
        FakeConnection first = awaitConnection(0);
        first.open();
        await(() -> listener.opens.get() == 1);

        first.failure(new IllegalStateException("scripted"));
        first.closed();

        await(() -> !client.send("probe"));
        advanceReconnectClock();
        awaitConnection(1);

        assertEquals(0, listener.terminations.get());
        assertEquals(List.of("open"), listener.events);
    }

    @Test
    public void closeIsIdempotentAndSuppressesLaterCallbacks()
            throws Exception {
        client.start(listener);
        FakeConnection connection = awaitConnection(0);
        connection.open();
        await(() -> listener.opens.get() == 1);

        client.close();
        int eventCount = listener.events.size();
        connection.text("late");
        Thread.sleep(30);
        client.close();

        assertFalse(client.send("late"));
        assertEquals(eventCount, listener.events.size());
        assertTrue(connection.socket.cancelled);
        assertTrue(resourcesClosed.get());
        assertEquals(0, listener.terminations.get());
    }

    @Test
    public void closeDoesNotWaitForAHandlerCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.start(new TextMessageClient.Listener() {
            @Override
            public void onOpen() {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onEndpointTerminated() {
            }
        });
        FakeConnection connection = awaitConnection(0);
        connection.open();
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        try {
            client.close();
        } finally {
            release.countDown();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt
        );

        assertTrue("close blocked for " + elapsedMillis + " ms",
                elapsedMillis < 500);
        assertTrue(connection.socket.cancelled);
        assertTrue(resourcesClosed.get());
    }

    @Test
    public void terminatesEndpointAfterBoundedUnstableAttempts()
            throws Exception {
        client.start(listener);
        awaitConnection(0).failure(new IOException("one"));
        advanceReconnectClock();
        awaitConnection(1).failure(new IOException("two"));
        advanceReconnectClock();
        awaitConnection(2).failure(new IOException("three"));

        await(() -> listener.terminations.get() == 1);
        FakeConnection terminated = awaitConnection(2);
        terminated.open();
        terminated.text("late");
        advanceReconnectClock();
        assertEquals(3, connector.connections.size());
        assertEquals(1, listener.terminations.get());
        assertEquals(0, listener.opens.get());
        assertFalse(listener.events.contains("message:late"));
        assertFalse(client.send("late"));
    }

    @Test
    public void stableConnectionResetsTheUnstableAttemptCount()
            throws Exception {
        client.start(listener);
        awaitConnection(0).failure(new IOException("one"));
        advanceReconnectClock();
        FakeConnection stable = awaitConnection(1);
        stable.open();
        await(() -> listener.opens.get() == 1);
        Thread.sleep(120);
        ShadowSystemClock.advanceBy(Duration.ofMillis(120));
        Thread.sleep(20);
        client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
        advanceReconnectClock();

        awaitConnection(2).failure(new IOException("three"));
        advanceReconnectClock();
        awaitConnection(3).failure(new IOException("four"));
        await(() -> listener.terminations.get() == 1);

        assertEquals(4, connector.connections.size());
    }

    private static TextMessageReconnectPolicy reconnectPolicy() {
        return TextMessageReconnectPolicy.of(
                3,
                Duration.ofMillis(10),
                Duration.ofMillis(100)
        );
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
            implements TextMessageClient.Listener {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger terminations = new AtomicInteger();
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
        public void onMessage(String message) {
            record("message:" + message);
        }

        @Override
        public void onEndpointTerminated() {
            terminations.incrementAndGet();
            record("terminated");
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

        private void failure(Throwable error) {
            listener.onFailure(socket, error, null);
        }

        private void closed() {
            listener.onClosed(socket, 1006, "closed");
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sent =
                new CopyOnWriteArrayList<>();
        private volatile boolean rejectNextSend;
        private volatile boolean cancelled;
        private volatile int closeCode = -1;

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
