package com.xa.mass.worker.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.PhoneInspectHandler;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebSocketWorkerTransportTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final FakeConnector connector = new FakeConnector();
    private WebSocketWorkerTransport transport;

    @BeforeEach
    void setUp() {
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                "worker-1",
                codec,
                Map.of(
                        PhoneInspectHandler.EVENT_CODE,
                        new PhoneInspectHandler()
                )
        );
        transport = new WebSocketWorkerTransport(
                connector,
                URI.create("http://127.0.0.1:18082"),
                "worker-1",
                Duration.ofHours(1),
                codec,
                processor
        );
        transport.start();
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    void fragmentedCommandMaintainsSerialBackpressure() {
        FakeWebSocket socket = connector.socket;
        transport.onOpen(socket);
        assertTrue(transport.isConnected());
        assertEquals(1, socket.requestCount);

        String command = command();
        int midpoint = command.length() / 2;
        transport.onText(socket, command.substring(0, midpoint), false);
        assertEquals(2, socket.requestCount);

        transport.onText(socket, command.substring(midpoint), true);
        assertEquals(1, socket.sentTexts.size());
        assertEquals(2, socket.requestCount);
        var resultMessage = codec.decodeWorkerConnectionMessage(
                socket.sentTexts.getFirst()
        );
        assertTrue(resultMessage instanceof TaskItemResultMessage);
        SeedResult result = ((TaskItemResultMessage) resultMessage).result();
        assertEquals("200", result.outcomeCode());

        socket.completeSend();
        assertEquals(3, socket.requestCount);
        assertFalse(transport.hasPendingResult());

        transport.close();
        assertFalse(transport.isConnected());
    }

    @Test
    void failedResultSendIsRetainedForReconnect() {
        FakeWebSocket first = connector.socket;
        transport.onOpen(first);
        transport.onText(first, command(), true);
        first.failSend();

        assertTrue(transport.hasPendingResult());
        assertTrue(first.aborted);

        FakeWebSocket second = new FakeWebSocket();
        transport.onOpen(second);
        assertEquals(1, second.sentTexts.size());
        second.completeSend();

        assertFalse(transport.hasPendingResult());
        assertEquals(1, second.requestCount);
    }

    @Test
    void synchronousSendFailureIsAlsoRetained() {
        FakeWebSocket first = connector.socket;
        first.throwOnSend = true;
        transport.onOpen(first);

        transport.onText(first, command(), true);

        assertTrue(transport.hasPendingResult());
        assertTrue(first.aborted);
    }

    @Test
    void invalidTextAndBinaryCloseTheCurrentConnection() {
        FakeWebSocket invalidText = connector.socket;
        transport.onOpen(invalidText);
        transport.onText(invalidText, "{bad-json", true);
        assertEquals(1007, invalidText.closeCode);

        FakeWebSocket binary = new FakeWebSocket();
        transport.onOpen(binary);
        transport.onBinary(binary, ByteBuffer.wrap(new byte[]{1}), true);
        assertEquals(1003, binary.closeCode);
    }

    @Test
    void websocketPathContainsOnlyTheTargetWorker() {
        assertEquals(
                URI.create(
                        "ws://127.0.0.1:18082/api/v1/worker-delivery/"
                                + "websocket/workers/worker-1"
                ),
                transport.socketUri()
        );
    }

    private String command() {
        String deliveryItem = """
                {"eventCode":"telecom.phone.inspect",\
                "payload":{"phoneNumber":"+14155552671"}}\
                """;
        return codec.encodeWorkerConnectionMessage(
                new TaskItemCommandMessage(new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        System.currentTimeMillis() + 10_000,
                        codec.encodeDeliverSeed(new DeliverSeed(
                                "worker-1",
                                deliveryItem,
                                "context"
                        ))
                ))
        );
    }

    private static final class FakeConnector
            implements WebSocketWorkerTransport.WebSocketConnector {

        private final FakeWebSocket socket = new FakeWebSocket();

        @Override
        public CompletableFuture<WebSocket> connect(
                URI uri,
                WebSocket.Listener listener
        ) {
            return CompletableFuture.completedFuture(socket);
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sentTexts = new ArrayList<>();
        private CompletableFuture<WebSocket> pendingSend;
        private int requestCount;
        private int closeCode = -1;
        private boolean aborted;
        private boolean throwOnSend;

        @Override
        public CompletableFuture<WebSocket> sendText(
                CharSequence data,
                boolean last
        ) {
            if (throwOnSend) {
                throw new IllegalStateException("socket is closed");
            }
            sentTexts.add(data.toString());
            pendingSend = new CompletableFuture<>();
            return pendingSend;
        }

        private void completeSend() {
            pendingSend.complete(this);
        }

        private void failSend() {
            pendingSend.completeExceptionally(
                    new IllegalStateException("send failed")
            );
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(
                ByteBuffer data,
                boolean last
        ) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(
                int statusCode,
                String reason
        ) {
            closeCode = statusCode;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
            requestCount += (int) n;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return closeCode >= 0 || aborted;
        }

        @Override
        public boolean isInputClosed() {
            return closeCode >= 0 || aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
