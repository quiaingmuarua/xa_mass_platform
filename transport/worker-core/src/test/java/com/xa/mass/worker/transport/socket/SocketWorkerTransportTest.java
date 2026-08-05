package com.xa.mass.worker.transport.socket;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.transport.client.LineSocketClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SocketWorkerTransportTest {

    private static final String COMMAND = "{\"command\":\"opaque\"}";
    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final FakeLineSocketClient client =
            new FakeLineSocketClient();
    private final AtomicReference<String> executedCommand =
            new AtomicReference<>();
    private SocketWorkerTransport transport;

    @BeforeEach
    void setUp() {
        WorkerCommandExecutor executor = encoded -> {
            executedCommand.set(encoded);
            return Optional.of(result());
        };
        transport = new SocketWorkerTransport(
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
    void bindCommandAndResultRemainTransportProtocolWork() {
        client.open();
        client.line(COMMAND);

        assertEquals(COMMAND, executedCommand.get());
        assertEquals(
                bind(),
                codec.decodeWorkerConnectionBind(client.sent.get(0))
        );
        assertEquals(
                result(),
                codec.decodeWorkerResult(client.sent.get(1))
        );
        assertFalse(transport.hasPendingResult());
        assertTrue(transport.isConnected());
    }

    @Test
    void rejectedNetworkSendRetainsResultAcrossReconnect() {
        client.open();
        client.rejectNextSend = true;

        client.line(COMMAND);

        assertTrue(transport.hasPendingResult());
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

    private static final class FakeLineSocketClient
            implements LineSocketClient {

        private final List<String> sent = new ArrayList<>();
        private Listener listener;
        private boolean connected;
        private boolean rejectNextSend;
        private boolean closed;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public boolean sendLine(String message) {
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

        private void line(String message) {
            listener.onLine(message);
        }
    }
}
