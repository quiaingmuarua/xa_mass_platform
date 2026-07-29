package com.xa.mass.worker.transport.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
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
                Map.of(
                        "test.observe",
                        WorkerEventDefinition.map(payload -> {
                            Map<String, Object> result =
                                    new LinkedHashMap<>();
                            result.put("observed", payload.get("value"));
                            return Jsons.toJson(result);
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
                decodeBind(socket.sentTexts.get(0))
        );

        connector.text(socket, command());
        await(() -> socket.sentTexts.size() == 2);

        WorkerResult result = decodeResult(socket.sentTexts.get(1));
        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"input\"}",
                result.payload()
        );
        assertEquals("context", result.forward());
        assertEquals(TASK, result.dst());
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
                decodeBind(second.sentTexts.get(0))
        );
        assertEquals(
                "200",
                decodeResult(second.sentTexts.get(1)).outcomeCode()
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void malformedAndBinaryMessagesCloseTheCurrentConnection()
            throws Exception {
        FakeWebSocket malformed = connector.socket;
        connector.open(malformed);
        connector.text(malformed, "{bad-json");
        await(() -> malformed.closeCode == 1007);
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
        WorkerCommand command = new WorkerCommand(
                COMMAND_ID,
                TASK,
                WORKER,
                "test.observe",
                System.currentTimeMillis() + 60_000,
                "{\"value\":\"input\"}",
                "context"
        );
        return codec.encodeWorkerCommand(command);
    }

    private WorkerConnectionBind decodeBind(String encoded) {
        return codec.decodeWorkerConnectionBind(encoded);
    }

    private WorkerResult decodeResult(String encoded) {
        return codec.decodeWorkerResult(encoded);
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
