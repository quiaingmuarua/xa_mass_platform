package com.xa.mass.worker.transport.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.worker.execution.PhoneInspectHandler;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.WorkerTransportException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollingWorkerTransportTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private final AtomicReference<String> nextCommand =
            new AtomicReference<>();
    private final AtomicReference<String> receivedResult =
            new AtomicReference<>();
    private final AtomicInteger pollCount = new AtomicInteger();
    private final AtomicInteger resultStatus = new AtomicInteger(202);
    private HttpServer server;
    private PollingWorkerTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/api/v1/worker-delivery/",
                this::handle
        );
        server.start();
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                "worker 1",
                codec,
                Map.of(
                        PhoneInspectHandler.EVENT_CODE,
                        new PhoneInspectHandler()
                )
        );
        transport = new PollingWorkerTransport(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "system polling",
                "worker 1",
                Duration.ofSeconds(2),
                codec,
                processor
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void pointPollingUsesEncodedTargetAndHandles204() throws Exception {
        assertFalse(transport.runOnce());
        assertEquals(1, pollCount.get());
        assertTrue(
                transport.pollUri().getRawPath().contains(
                        "system%20polling/workers/worker%201/commands:poll"
                )
        );
        assertFalse(transport.pollUri().getPath().contains("consume"));
    }

    @Test
    void successIsSubmittedAndAccepted() throws Exception {
        nextCommand.set(command(System.currentTimeMillis() + 10_000));

        assertTrue(transport.runOnce());

        String result = receivedResult.get();
        assertTrue(result.contains("\"outcomeCode\":\"200\""));
        assertTrue(result.contains("+14155552671"));
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void pendingResultRetriesBeforeAnotherPoll() throws Exception {
        nextCommand.set(command(System.currentTimeMillis() + 10_000));
        resultStatus.set(503);

        assertThrows(WorkerTransportException.class, transport::runOnce);
        assertTrue(transport.hasPendingResult());
        assertEquals(1, pollCount.get());

        resultStatus.set(202);
        assertTrue(transport.runOnce());
        assertFalse(transport.hasPendingResult());
        assertEquals(1, pollCount.get());
    }

    @Test
    void expiredCommandIsDroppedWithoutResult() throws Exception {
        nextCommand.set(command(System.currentTimeMillis() - 1));

        assertFalse(transport.runOnce());

        assertEquals(null, receivedResult.get());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/commands:poll")) {
            pollCount.incrementAndGet();
            String body = nextCommand.getAndSet(null);
            if (body == null) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, encoded.length);
                exchange.getResponseBody().write(encoded);
            }
        } else if (path.endsWith("/results")) {
            receivedResult.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            exchange.sendResponseHeaders(resultStatus.get(), -1);
        } else {
            exchange.sendResponseHeaders(404, -1);
        }
        exchange.close();
    }

    private String command(long deadline) {
        String deliveryItem = """
                {"eventCode":"telecom.phone.inspect",\
                "payload":{"phoneNumber":"+14155552671"}}\
                """;
        return codec.encodeWorkerCommand(new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                deadline,
                codec.encodeDeliverSeed(new DeliverSeed(
                        "worker 1",
                        deliveryItem,
                        "context"
                ))
        ));
    }
}
