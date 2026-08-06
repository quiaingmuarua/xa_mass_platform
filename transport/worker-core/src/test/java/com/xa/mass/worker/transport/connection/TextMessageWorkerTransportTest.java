package com.xa.mass.worker.transport.connection;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextMessageWorkerTransportTest {

    private static final String COMMAND = "{\"command\":\"opaque\"}";
    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final FakeTextMessageClient client =
            new FakeTextMessageClient();
    private final AtomicReference<String> executedCommand =
            new AtomicReference<>();
    private TextMessageWorkerTransport transport;

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
        transport = new TextMessageWorkerTransport(
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

        client.message(COMMAND);
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

        client.message(COMMAND);
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
    void protocolFailureClosesThroughClientBoundary()
            throws Exception {
        client.open();
        client.message("{bad-json");
        await(() -> client.lastCloseReason
                == TextMessageClient.CloseReason.PROTOCOL_ERROR);
    }

    @Test
    void messageBeforeBindIsAProtocolFailure() {
        client.message(COMMAND);

        assertEquals(
                TextMessageClient.CloseReason.PROTOCOL_ERROR,
                client.lastCloseReason
        );
    }

    @Test
    void rejectedBindClosesAsSendFailure() {
        client.rejectNextSend = true;

        client.open();

        assertEquals(
                TextMessageClient.CloseReason.SEND_FAILURE,
                client.lastCloseReason
        );
        assertFalse(transport.isConnected());
    }

    @Test
    void commandExecutionIsDedicatedAndConcurrentInputClosesConnection()
            throws Exception {
        FakeTextMessageClient isolatedClient =
                new FakeTextMessageClient();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> executionThread = new AtomicReference<>();
        TextMessageWorkerTransport isolatedTransport =
                new TextMessageWorkerTransport(
                        isolatedClient,
                        COMMAND_ID,
                        encoded -> {
                            executionThread.set(
                                    Thread.currentThread().getName()
                            );
                            entered.countDown();
                            try {
                                release.await(2, TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                return Optional.empty();
                            }
                            return Optional.of(result());
                        }
                );
        try {
            isolatedTransport.start();
            isolatedClient.open();
            String callbackThread = Thread.currentThread().getName();

            isolatedClient.message(COMMAND);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotEquals(callbackThread, executionThread.get());

            isolatedClient.message("{\"command\":\"second\"}");
            assertEquals(
                    TextMessageClient.CloseReason.PROTOCOL_ERROR,
                    isolatedClient.lastCloseReason
            );
        } finally {
            release.countDown();
            isolatedTransport.close();
        }
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

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private final List<String> sent = new ArrayList<>();
        private Listener listener;
        private boolean connected;
        private boolean rejectNextSend;
        private boolean closed;
        private CloseReason lastCloseReason;

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
        public void closeCurrent(CloseReason reason) {
            lastCloseReason = reason;
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

        private void message(String message) {
            listener.onMessage(message);
        }
    }
}
