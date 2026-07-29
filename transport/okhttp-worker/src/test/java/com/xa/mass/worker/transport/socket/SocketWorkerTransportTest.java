package com.xa.mass.worker.transport.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionMessageType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SocketWorkerTransportTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @Test
    void bindsThenRetriesPendingResultBeforeReadingTheNextCommand()
            throws Exception {
        String commandLine = command() + "\r\n";
        FailAfterFirstLineOutput firstOutput =
                new FailAfterFirstLineOutput();
        ScriptedSocket first = new ScriptedSocket(
                new ByteArrayInputStream(
                        commandLine.getBytes(StandardCharsets.UTF_8)
                ),
                firstOutput
        );
        BlockingInput secondInput = new BlockingInput();
        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        ScriptedSocket second = new ScriptedSocket(
                secondInput,
                secondOutput
        );
        ArrayDeque<Socket> sockets = new ArrayDeque<>();
        sockets.add(first);
        sockets.add(second);
        AtomicInteger connects = new AtomicInteger();
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                "worker-1",
                codec,
                Map.of(
                        "test.observe",
                        WorkerEventDefinition.map(payload -> Jsons.toJson(
                                Map.of(
                                "observed",
                                payload.get("value")
                        )))
                )
        );
        SocketWorkerTransport transport = new SocketWorkerTransport(
                (uri, timeout) -> {
                    connects.incrementAndGet();
                    Socket socket = sockets.poll();
                    if (socket == null) {
                        throw new IOException("No scripted socket");
                    }
                    return socket;
                },
                URI.create("tcp://127.0.0.1:18084"),
                "worker-1",
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                codec,
                processor
        );
        Thread worker = new Thread(() -> {
            try {
                transport.runForever();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        await(() -> lineCount(secondOutput) >= 2);

        String[] firstLines = lines(firstOutput.bytes());
        assertEquals(1, firstLines.length);
        assertEquals(
                new WorkerConnectionBind("worker-1"),
                decodeBind(firstLines[0])
        );

        String[] secondLines = lines(secondOutput);
        assertEquals(
                new WorkerConnectionBind("worker-1"),
                decodeBind(secondLines[0])
        );
        assertEquals(
                "200",
                decodeResult(secondLines[1]).outcomeCode()
        );
        assertTrue(connects.get() >= 2);
        assertFalse(transport.hasPendingResult());

        transport.close();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertFalse(transport.isConnected());
    }

    private String command() {
        String deliveryItem = "{\"eventCode\":\"test.observe\","
                + "\"payload\":{\"value\":\"input\"}}";
        WorkerCommandEnvelope command = new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                System.currentTimeMillis() + 10_000,
                codec.encodeDeliverSeed(new DeliverSeed(
                        "worker-1",
                        deliveryItem,
                        "context"
                ))
        );
        return codec.encodeWorkerConnectionMessage(
                new WorkerConnectionMessage(
                        WorkerConnectionMessageType
                                .TASK_ITEM_COMMAND.name(),
                        codec.encodeWorkerCommand(command)
                )
        );
    }

    private WorkerConnectionBind decodeBind(String encoded) {
        WorkerConnectionMessage message =
                codec.decodeWorkerConnectionMessage(encoded);
        assertEquals(
                WorkerConnectionMessageType.WORKER_BIND.name(),
                message.messageType()
        );
        return codec.decodeWorkerConnectionBind(message.payload());
    }

    private SeedResult decodeResult(String encoded) {
        WorkerConnectionMessage message =
                codec.decodeWorkerConnectionMessage(encoded);
        assertEquals(
                WorkerConnectionMessageType.TASK_ITEM_RESULT.name(),
                message.messageType()
        );
        return codec.decodeSeedResult(message.payload());
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.satisfied()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Condition was not satisfied");
    }

    private static int lineCount(ByteArrayOutputStream output) {
        return lines(output).length;
    }

    private static String[] lines(ByteArrayOutputStream output) {
        return lines(output.toByteArray());
    }

    private static String[] lines(byte[] output) {
        return new String(output, StandardCharsets.UTF_8)
                .strip()
                .split("\\n");
    }

    @FunctionalInterface
    private interface Condition {

        boolean satisfied();
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

    private static final class FailAfterFirstLineOutput
            extends OutputStream {

        private final ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();
        private int lineCount;

        @Override
        public synchronized void write(int value) throws IOException {
            if (lineCount >= 1) {
                throw new IOException("scripted result failure");
            }
            bytes.write(value);
            if (value == '\n') {
                lineCount++;
            }
        }

        private synchronized byte[] bytes() {
            return bytes.toByteArray();
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
