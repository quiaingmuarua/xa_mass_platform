package com.xa.mass.worker.javase;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaLineSocketClientTest {

    private ExecutorService socketExecutor;

    @BeforeEach
    void setUp() {
        socketExecutor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        socketExecutor.shutdownNow();
    }

    @Test
    void readsLfAndCrLfAndWritesOneLfTerminatedLine()
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ScriptedSocket socket = new ScriptedSocket(
                new ByteArrayInputStream(
                        "one\r\ntwo\n".getBytes(StandardCharsets.UTF_8)
                ),
                output
        );
        AtomicInteger connects = new AtomicInteger();
        AtomicReference<JavaLineSocketClient> reference =
                new AtomicReference<>();
        RecordingListener listener = new RecordingListener() {
            @Override
            public void onOpen() {
                super.onOpen();
                assertTrue(reference.get().send("result"));
            }
        };
        JavaLineSocketClient client = new JavaLineSocketClient(
                socketExecutor,
                (uri, timeout) -> {
                    if (connects.getAndIncrement() == 0) {
                        return socket;
                    }
                    throw new IOException("scripted retry");
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                policy(20, 50)
        );
        reference.set(client);

        client.start(listener);
        await(() -> listener.lines.size() == 2);

        assertEquals(List.of("one", "two"), listener.lines);
        assertEquals(
                "result\n",
                output.toString(StandardCharsets.UTF_8.name())
        );
        client.close();
        assertFalse(client.send("late"));
    }

    @Test
    void connectionFailureRetriesUntilAConnectionOpens()
            throws Exception {
        BlockingInput input = new BlockingInput();
        ScriptedSocket socket = new ScriptedSocket(
                input,
                new ByteArrayOutputStream()
        );
        AtomicInteger connects = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        JavaLineSocketClient client = new JavaLineSocketClient(
                socketExecutor,
                (uri, timeout) -> {
                    if (connects.getAndIncrement() == 0) {
                        throw new IOException("scripted connect failure");
                    }
                    return socket;
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                policy(20, 5)
        );

        client.start(listener);
        await(() -> listener.opens.get() == 1);

        assertTrue(client.send("result"));
        assertTrue(connects.get() >= 2);
        assertEquals(0, listener.terminations.get());
        client.close();
    }

    @Test
    void closeCurrentClosesOnlyTheActiveSocketAndReconnects()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        JavaLineSocketClient client = new JavaLineSocketClient(
                socketExecutor,
                (uri, timeout) -> new ScriptedSocket(
                        new BlockingInput(),
                        new ByteArrayOutputStream()
                ),
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                policy(20, 5)
        );

        client.start(listener);
        await(() -> listener.opens.get() == 1);
        client.closeCurrent(TextMessageClient.CloseReason.PROTOCOL_ERROR);
        await(() -> listener.opens.get() == 2);

        assertEquals(0, listener.terminations.get());
        assertTrue(client.send("result"));
        client.close();
        assertEquals(0, listener.terminations.get());
    }

    @Test
    void terminatesEndpointAfterBoundedConnectionFailures()
            throws Exception {
        RecordingListener listener = new RecordingListener();
        AtomicInteger connects = new AtomicInteger();
        JavaLineSocketClient client = new JavaLineSocketClient(
                socketExecutor,
                (uri, timeout) -> {
                    connects.incrementAndGet();
                    throw new IOException("scripted connect failure");
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                policy(3, 5)
        );

        client.start(listener);
        await(() -> listener.terminations.get() == 1);
        Thread.sleep(20);

        assertEquals(3, connects.get());
        assertEquals(1, listener.terminations.get());
        assertFalse(client.send("late"));
        client.close();
    }

    @Test
    void stableConnectionResetsTheUnstableAttemptCount()
            throws Exception {
        AtomicInteger connects = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        JavaLineSocketClient client = new JavaLineSocketClient(
                socketExecutor,
                (uri, timeout) -> {
                    int attempt = connects.getAndIncrement();
                    if (attempt == 0 || attempt >= 2) {
                        throw new IOException("scripted connect failure");
                    }
                    return new ScriptedSocket(
                            new BlockingInput(),
                            new ByteArrayOutputStream()
                    );
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                policy(3, 5)
        );

        client.start(listener);
        await(() -> listener.opens.get() == 1);
        Thread.sleep(60);
        client.closeCurrent(TextMessageClient.CloseReason.NORMAL);
        await(() -> listener.terminations.get() == 1);

        assertEquals(4, connects.get());
        client.close();
    }

    private static TextMessageReconnectPolicy policy(
            int maxAttempts,
            long reconnectMillis
    ) {
        return TextMessageReconnectPolicy.of(
                maxAttempts,
                Duration.ofMillis(reconnectMillis),
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

    private static class RecordingListener
            implements TextMessageClient.Listener {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger terminations = new AtomicInteger();
        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen() {
            opens.incrementAndGet();
        }

        @Override
        public void onMessage(String message) {
            lines.add(message);
        }

        @Override
        public void onEndpointTerminated() {
            terminations.incrementAndGet();
        }
    }

    private static final class ScriptedSocket extends Socket {

        private final InputStream input;
        private final OutputStream output;
        private volatile boolean closed;

        private ScriptedSocket(
                InputStream input,
                OutputStream output
        ) {
            this.input = input;
            this.output = output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            input.close();
            output.close();
        }
    }

    private static final class BlockingInput extends InputStream {

        private boolean closed;

        @Override
        public synchronized int read() throws IOException {
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
