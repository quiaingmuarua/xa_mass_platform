package com.xa.mass.worker.transport.websocket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSocketWorkerTransportTest {

    private static final String COMMAND = "{\"command\":\"opaque\"}";
    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final FakeTextWebSocketClient client =
            new FakeTextWebSocketClient();
    private final AtomicReference<String> executedCommand =
            new AtomicReference<>();
    private WebSocketWorkerTransport transport;

    @BeforeEach
    void setUp() {
        WorkerCommandExecutor executor = encoded -> {
            if ("{bad-json".equals(encoded)) {
                throw new WorkerException(
                        WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                        "command.decode",
                        null,
                        null
                );
            }
            executedCommand.set(encoded);
            return Optional.of(result());
        };
        transport = new WebSocketWorkerTransport(
                client,
                COMMAND_ID,
                executor
        );
        transport.start();
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    void bindCommandAndResultRemainTransportProtocolWork()
            throws Exception {
        client.open();

        assertTrue(transport.isConnected());
        assertEquals(
                bind(),
                codec.decodeWorkerConnectionBind(client.sent.get(0))
        );

        client.text(COMMAND);
        await(() -> client.sent.size() == 2);

        assertEquals(COMMAND, executedCommand.get());
        assertEquals(
                result(),
                codec.decodeWorkerResult(client.sent.get(1))
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void rejectedNetworkSendRetainsResultAcrossReconnect()
            throws Exception {
        client.open();
        client.rejectNextSend = true;

        client.text(COMMAND);
        await(transport::hasPendingResult);
        assertFalse(transport.isConnected());

        client.open();

        assertEquals(3, client.sent.size());
        assertEquals(
                bind(),
                codec.decodeWorkerConnectionBind(client.sent.get(1))
        );
        assertEquals(
                result(),
                codec.decodeWorkerResult(client.sent.get(2))
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void protocolAndBinaryFailuresCloseThroughClientBoundary()
            throws Exception {
        client.open();
        client.text("{bad-json");
        await(() -> client.lastCloseCode == 1007);

        client.open();
        client.binary();
        assertEquals(1003, client.lastCloseCode);
    }

    @Test
    void closeOwnsNetworkClientAndIsIdempotent() {
        client.open();

        transport.close();
        transport.close();

        assertTrue(client.closed);
        assertFalse(transport.isConnected());
    }

    private static WorkerResult result() {
        return new WorkerResult(
                COMMAND_ID,
                TASK,
                "test.observe",
                "200",
                "{\"observed\":\"input\"}",
                "context"
        );
    }

    private static WorkerConnectionBind bind() {
        return new WorkerConnectionBind(COMMAND_ID);
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

    private static final class FakeTextWebSocketClient
            implements TextWebSocketClient {

        private final List<String> sent = new ArrayList<>();
        private Listener listener;
        private boolean connected;
        private boolean rejectNextSend;
        private boolean closed;
        private int lastCloseCode = -1;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public boolean send(String message) {
            if (!connected) {
                return false;
            }
            if (rejectNextSend) {
                rejectNextSend = false;
                connected = false;
                listener.onDisconnected();
                return false;
            }
            sent.add(message);
            return true;
        }

        @Override
        public void closeCurrent(int code, String reason) {
            lastCloseCode = code;
            if (connected) {
                connected = false;
                listener.onDisconnected();
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }

        private void open() {
            connected = true;
            listener.onOpen();
        }

        private void text(String message) {
            listener.onText(message);
        }

        private void binary() {
            listener.onBinary();
        }
    }
}
