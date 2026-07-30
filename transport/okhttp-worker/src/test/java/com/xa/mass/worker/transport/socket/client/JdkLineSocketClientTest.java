package com.xa.mass.worker.transport.socket.client;

import com.xa.mass.transport.client.LineSocketClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkLineSocketClientTest {

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
        AtomicReference<JdkLineSocketClient> reference =
                new AtomicReference<>();
        RecordingListener listener = new RecordingListener() {
            @Override
            public void onOpen() {
                super.onOpen();
                assertTrue(reference.get().sendLine("result"));
            }
        };
        JdkLineSocketClient client = new JdkLineSocketClient(
                (uri, timeout) -> {
                    if (connects.getAndIncrement() == 0) {
                        return socket;
                    }
                    throw new IOException("scripted retry");
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                Duration.ofMillis(50)
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
        assertFalse(client.isConnected());
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
        JdkLineSocketClient client = new JdkLineSocketClient(
                (uri, timeout) -> {
                    if (connects.getAndIncrement() == 0) {
                        throw new IOException("scripted connect failure");
                    }
                    return socket;
                },
                URI.create("tcp://127.0.0.1:18084"),
                Duration.ofSeconds(1),
                Duration.ofMillis(5)
        );

        client.start(listener);
        await(() -> listener.opens.get() == 1);

        assertTrue(client.isConnected());
        assertTrue(connects.get() >= 2);
        assertEquals(1, listener.failures.get());
        client.close();
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
            implements LineSocketClient.Listener {

        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final List<String> lines = new ArrayList<>();

        @Override
        public void onOpen() {
            opens.incrementAndGet();
        }

        @Override
        public void onLine(String message) {
            lines.add(message);
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onFailure(Throwable error) {
            failures.incrementAndGet();
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
