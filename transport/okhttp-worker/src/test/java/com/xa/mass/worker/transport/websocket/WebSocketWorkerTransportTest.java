package com.xa.mass.worker.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemCommandMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.TaskItemResultMessage;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
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
                        "test.observe",
                        WorkerEventDefinition.map(payload -> {
                            Map<String, Object> result =
                                    new LinkedHashMap<>();
                            result.put("observed", payload.get("value"));
                            return result;
                        })
                )
        );
        transport = new WebSocketWorkerTransport(
                new WebSocketWorkerTransport.ConnectorResources(
                        connector,
                        () -> {
                        }
                ),
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
    void bindCommandAndResultUseOneSerialConnection() throws Exception {
        FakeWebSocket socket = connector.socket;
        connector.open(socket);

        assertTrue(transport.isConnected());
        assertEquals(
                new WorkerConnectionBind("worker-1"),
                codec.decodeWorkerConnectionBind(
                        socket.sentTexts.get(0)
                )
        );

        connector.text(socket, command());
        await(() -> socket.sentTexts.size() == 2);

        TaskItemResultMessage resultMessage =
                (TaskItemResultMessage)
                        codec.decodeWorkerConnectionMessage(
                                socket.sentTexts.get(1)
                        );
        SeedResult result = resultMessage.result();
        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"input\"}",
                result.opaqueResultPayload()
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void failedResultSendIsRetainedAndSentAfterReconnect()
            throws Exception {
        FakeWebSocket first = connector.socket;
        connector.open(first);
        first.rejectNextSend = true;

        connector.text(first, command());
        await(transport::hasPendingResult);
        assertTrue(first.cancelled);

        FakeWebSocket second = new FakeWebSocket();
        connector.open(second);

        assertEquals(2, second.sentTexts.size());
        assertEquals(
                new WorkerConnectionBind("worker-1"),
                codec.decodeWorkerConnectionBind(
                        second.sentTexts.get(0)
                )
        );
        assertTrue(
                codec.decodeWorkerConnectionMessage(
                        second.sentTexts.get(1)
                ) instanceof TaskItemResultMessage
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void malformedAndBinaryMessagesCloseTheCurrentConnection() {
        FakeWebSocket malformed = connector.socket;
        connector.open(malformed);
        connector.text(malformed, "{bad-json");
        assertEquals(1007, malformed.closeCode);

        FakeWebSocket binary = new FakeWebSocket();
        connector.open(binary);
        connector.binary(binary, ByteString.of((byte) 1));
        assertEquals(1003, binary.closeCode);
    }

    @Test
    void websocketPathDoesNotCarryWorkerIdentity() {
        assertEquals(
                URI.create(
                        "ws://127.0.0.1:18082/api/v1/"
                                + "worker-delivery/websocket"
                ),
                transport.socketUri()
        );
    }

    @Test
    void closeIsIdempotentAndStopsTheConnection() {
        connector.open(connector.socket);

        transport.close();
        transport.close();

        assertFalse(transport.isConnected());
        assertTrue(connector.socket.cancelled);
    }

    private String command() {
        return codec.encodeWorkerConnectionMessage(
                new TaskItemCommandMessage(new WorkerCommandEnvelope(
                        COMMAND_ID,
                        WorkerMessageType.TASK_ITEM,
                        System.currentTimeMillis() + 60_000,
                        codec.encodeDeliverSeed(new DeliverSeed(
                                "worker-1",
                                "{\"eventCode\":\"test.observe\","
                                        + "\"payload\":{\"value\":\"input\"}}",
                                "context"
                        ))
                ))
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

    private static final class FakeConnector
            implements WebSocketWorkerTransport.WebSocketConnector {

        private final FakeWebSocket socket = new FakeWebSocket();
        private WebSocketListener listener;

        @Override
        public WebSocket connect(
                URI uri,
                WebSocketListener listener
        ) {
            this.listener = listener;
            return socket;
        }

        private void open(WebSocket webSocket) {
            listener.onOpen(webSocket, null);
        }

        private void text(WebSocket webSocket, String value) {
            listener.onMessage(webSocket, value);
        }

        private void binary(WebSocket webSocket, ByteString value) {
            listener.onMessage(webSocket, value);
        }
    }

    private static final class FakeWebSocket implements WebSocket {

        private final List<String> sentTexts = new ArrayList<>();
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
            sentTexts.add(text);
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
