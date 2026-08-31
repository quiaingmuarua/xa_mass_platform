package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaOkHttpTextWebSocketClientTest {

    private final FakeConnector connector = new FakeConnector();
    private final RecordingListener listener = new RecordingListener();
    private ScheduledExecutorService networkExecutor;
    private JavaOkHttpTextWebSocketClient client;

    @BeforeEach
    void setUp() throws Exception {
        networkExecutor = Executors.newSingleThreadScheduledExecutor();
        client = new JavaOkHttpTextWebSocketClient(
                connector,
                networkExecutor,
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
        networkExecutor.shutdownNow();
    }

    @Test
    void forwardsOnlyTextMessagesAndSendsText() throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(() -> listener.opens.get() == 1);
        first.text("command");
        await(() -> listener.messages.size() == 1);

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
        assertEquals(0, listener.terminations.get());
        assertTrue(client.send("result"));
        assertEquals(1007, first.socket.closeCode);
    }

    @Test
    void rejectedSendIsClosedByTheTransportBoundaryAndReconnects()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(() -> listener.opens.get() == 1);
        first.socket.rejectNextSend = true;

        assertFalse(client.send("result"));
        client.closeCurrent(TextMessageClient.CloseReason.SEND_FAILURE);
        await(() -> connector.connections.size() == 2);

        assertEquals(1011, first.socket.closeCode);
        assertEquals(0, listener.terminations.get());
    }

    @Test
    void binaryFrameIsRejectedInsideTheWebSocketClient()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        await(() -> listener.opens.get() == 1);

        first.binary();
        await(() -> connector.connections.size() == 2);

        assertEquals(1003, first.socket.closeCode);
        assertTrue(listener.messages.isEmpty());
    }

    @Test
    void listenerCallbacksRunDirectlyInProtocolOrder() throws Exception {
        FakeConnection first = connector.connections.get(0);
        String callbackThread = Thread.currentThread().getName();
        first.open();
        first.text("one");
        first.text("two");
        await(() -> listener.messages.size() == 2);

        assertEquals(
                List.of(callbackThread, callbackThread, callbackThread),
                listener.callbackThreads
        );
    }

    @Test
    void slowCallbackDoesNotBlockSharedNetworkScheduler()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch scheduled = new CountDownLatch(1);
        JavaOkHttpTextWebSocketClient blockingClient =
                new JavaOkHttpTextWebSocketClient(
                        connector,
                        networkExecutor,
                        URI.create("ws://127.0.0.1:18083/slow"),
                        reconnectPolicy()
                );
        blockingClient.start(blockingMessageListener(entered, release));
        await(() -> connector.connections.size() == 2);
        FakeConnection connection = connector.connections.get(1);
        connection.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(
                    () -> connection.text("slow")
            );
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            networkExecutor.execute(scheduled::countDown);
            assertTrue(scheduled.await(1, TimeUnit.SECONDS));

            release.countDown();
            handling.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            callback.shutdownNow();
            blockingClient.close();
        }
    }

    @Test
    void externalCloseReturnsWhileCurrentCallbackFinishes()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JavaOkHttpTextWebSocketClient blockingClient =
                new JavaOkHttpTextWebSocketClient(
                        connector,
                        networkExecutor,
                        URI.create("ws://127.0.0.1:18083/close"),
                        reconnectPolicy()
                );
        blockingClient.start(blockingMessageListener(entered, release));
        await(() -> connector.connections.size() == 2);
        FakeConnection connection = connector.connections.get(1);
        connection.open();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> handling = callers.submit(
                    () -> connection.text("slow")
            );
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            Future<?> closing = callers.submit(blockingClient::close);
            closing.get(1, TimeUnit.SECONDS);
            assertTrue(connection.socket.cancelled);
            assertFalse(handling.isDone());

            release.countDown();
            handling.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            callers.shutdownNow();
            blockingClient.close();
        }
    }

    @Test
    void replacementAttemptMayDeliverWhileOldCallbackFinishes()
            throws Exception {
        client.close();
        int firstIndex = connector.connections.size();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        client = new JavaOkHttpTextWebSocketClient(
                connector,
                networkExecutor,
                URI.create("ws://127.0.0.1:18083/overlap"),
                reconnectPolicy()
        );
        client.start(new TextMessageClient.Listener() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onMessage(String message) {
                if ("first".equals(message)) {
                    firstEntered.countDown();
                    awaitLatch(releaseFirst);
                } else if ("second".equals(message)) {
                    secondDelivered.countDown();
                }
            }

            @Override
            public void onEndpointTerminated() {
            }
        });
        await(() -> connector.connections.size() > firstIndex);
        FakeConnection first = connector.connections.get(firstIndex);
        first.open();
        ExecutorService callback = Executors.newSingleThreadExecutor();
        try {
            Future<?> handling = callback.submit(() -> first.text("first"));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
            await(() -> connector.connections.size() > firstIndex + 1);
            FakeConnection second = connector.connections.get(
                    firstIndex + 1
            );
            second.open();
            second.text("second");

            assertTrue(secondDelivered.await(1, TimeUnit.SECONDS));
            assertFalse(handling.isDone());
            releaseFirst.countDown();
            handling.get(3, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            callback.shutdownNow();
        }
    }

    @Test
    void callbackMayCloseClientReentrantly() throws Exception {
        FakeConnection first = connector.connections.get(0);
        CountDownLatch closedFromCallback = new CountDownLatch(1);
        client.close();
        client = new JavaOkHttpTextWebSocketClient(
                connector,
                networkExecutor,
                URI.create("ws://127.0.0.1:18083/reentrant"),
                reconnectPolicy()
        );
        client.start(new TextMessageClient.Listener() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onMessage(String message) {
                client.close();
                closedFromCallback.countDown();
            }

            @Override
            public void onEndpointTerminated() {
            }
        });
        await(() -> connector.connections.size() == 2);
        FakeConnection connection = connector.connections.get(1);
        connection.open();

        connection.text("close");

        assertTrue(closedFromCallback.await(1, TimeUnit.SECONDS));
        assertTrue(connection.socket.cancelled);
        assertTrue(first.socket.cancelled);
    }

    @Test
    void closeIsIdempotentAndDoesNotCloseBorrowedResources() {
        FakeConnection first = connector.connections.get(0);
        first.open();

        client.close();
        client.close();
        first.open();
        first.text("late");

        assertFalse(client.send("late"));
        assertTrue(first.socket.cancelled);
        assertFalse(networkExecutor.isShutdown());
        assertEquals(0, listener.terminations.get());
        assertEquals(1, listener.opens.get());
        assertTrue(listener.messages.isEmpty());
    }

    @Test
    void competingAttemptCompletionSchedulesOneReconnect()
            throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        ExecutorService callers = Executors.newFixedThreadPool(3);
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch race = new CountDownLatch(1);
        try {
            List<Future<?>> completions = List.of(
                    callers.submit(() -> raceAttempt(
                            ready,
                            race,
                            first::fail
                    )),
                    callers.submit(() -> raceAttempt(
                            ready,
                            race,
                            first::fail
                    )),
                    callers.submit(() -> raceAttempt(
                            ready,
                            race,
                            () -> client.closeCurrent(
                                    TextMessageClient.CloseReason.NORMAL
                            )
                    ))
            );
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            race.countDown();
            for (Future<?> completion : completions) {
                completion.get(1, TimeUnit.SECONDS);
            }
            await(() -> connector.connections.size() == 2);
            Thread.sleep(30L);

            assertEquals(2, connector.connections.size());
            assertEquals(0, listener.terminations.get());
        } finally {
            race.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void concurrentSendAndCloseRemainBestEffort() throws Exception {
        FakeConnection first = connector.connections.get(0);
        first.open();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch race = new CountDownLatch(1);
        try {
            Future<?> sending = callers.submit(() -> {
                awaitRace(race);
                for (int index = 0; index < 100; index++) {
                    client.send("result-" + index);
                }
            });
            Future<?> closing = callers.submit(() -> {
                awaitRace(race);
                client.close();
            });
            race.countDown();
            sending.get(1, TimeUnit.SECONDS);
            closing.get(1, TimeUnit.SECONDS);

            assertFalse(client.send("late"));
            assertTrue(first.socket.cancelled);
            assertEquals(0, listener.terminations.get());
        } finally {
            race.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void terminatesEndpointAfterBoundedUnstableAttempts() throws Exception {
        connector.connections.get(0).fail();
        await(() -> connector.connections.size() == 2);
        connector.connections.get(1).fail();
        await(() -> connector.connections.size() == 3);
        connector.connections.get(2).fail();

        await(() -> listener.terminations.get() == 1);
        connector.connections.get(2).open();
        connector.connections.get(2).text("late");
        Thread.sleep(30);
        assertEquals(3, connector.connections.size());
        assertEquals(1, listener.terminations.get());
        assertEquals(0, listener.opens.get());
        assertTrue(listener.messages.isEmpty());
        assertFalse(client.send("late"));
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

        await(() -> listener.terminations.get() == 1);
        assertEquals(4, connector.connections.size());
    }

    private static TextMessageReconnectPolicy reconnectPolicy() {
        return TextMessageReconnectPolicy.of(
                3,
                Duration.ofMillis(5),
                Duration.ofMillis(40)
        );
    }

    private static TextMessageClient.Listener blockingMessageListener(
            CountDownLatch entered,
            CountDownLatch release
    ) {
        return new TextMessageClient.Listener() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onMessage(String message) {
                entered.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onEndpointTerminated() {
            }
        };
    }

    private static void raceAttempt(
            CountDownLatch ready,
            CountDownLatch race,
            Runnable action
    ) {
        ready.countDown();
        try {
            race.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return;
        }
        action.run();
    }

    private static void awaitRace(CountDownLatch race) {
        try {
            race.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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
        private final AtomicInteger terminations = new AtomicInteger();
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
        public void onEndpointTerminated() {
            callbackThreads.add(Thread.currentThread().getName());
            terminations.incrementAndGet();
        }
    }

    private static final class FakeConnector
            implements JavaOkHttpTextWebSocketClient.WebSocketConnector {

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
