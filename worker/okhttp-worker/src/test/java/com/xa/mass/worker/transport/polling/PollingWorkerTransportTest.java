package com.xa.mass.worker.transport.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.transport.WorkerTransportException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollingWorkerTransportTest {

    private static final String WORKER_ID = "worker 1";
    private static final String COMMAND_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
    private MockWebServer server;
    private PollingWorkerTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                WORKER_ID,
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
        transport = new PollingWorkerTransport(
                URI.create(server.url("/").toString()),
                "system polling",
                WORKER_ID,
                Duration.ofSeconds(2),
                codec,
                processor
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (transport != null) {
            transport.close();
        }
        server.close();
    }

    @Test
    void pointPollingUsesEncodedTargetAndHandles204() throws Exception {
        server.enqueue(response(204, null));

        assertFalse(transport.runOnce());

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals(
                "/api/v1/worker-delivery/endpoint-managers/"
                        + "system%20polling/workers/worker%201/commands:poll",
                request.getTarget()
        );
        assertFalse(transport.pollUri().getPath().contains("consume"));
    }

    @Test
    void successIsSubmittedAndAccepted() throws Exception {
        server.enqueue(response(200, command()));
        server.enqueue(response(202, ""));

        assertTrue(transport.runOnce());

        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest submitted =
                server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(submitted);
        SeedResult result = codec.decodeSeedResult(
                submitted.getBody().utf8()
        );
        assertNotNull(result);
        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"input\"}",
                result.opaqueResultPayload()
        );
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void pendingResultRetriesBeforeAnotherPoll() throws Exception {
        server.enqueue(response(200, command()));
        server.enqueue(response(503, ""));

        assertThrows(WorkerTransportException.class, transport::runOnce);
        assertTrue(transport.hasPendingResult());
        assertEquals(2, server.getRequestCount());

        server.enqueue(response(202, ""));
        assertTrue(transport.runOnce());
        assertFalse(transport.hasPendingResult());
        assertEquals(3, server.getRequestCount());
        RecordedRequest retry =
                server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(retry);
    }

    @Test
    void expiredCommandIsDroppedWithoutResult() throws Exception {
        server.enqueue(response(200, command(
                System.currentTimeMillis() - 1
        )));

        assertFalse(transport.runOnce());
        assertFalse(transport.hasPendingResult());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void closeIsIdempotentAndPreventsMoreRounds() {
        transport.close();
        transport.close();

        assertThrows(IllegalStateException.class, transport::runOnce);
    }

    private String command() {
        return command(System.currentTimeMillis() + 60_000);
    }

    private String command(long deadline) {
        DeliverSeed seed = new DeliverSeed(
                WORKER_ID,
                "{\"eventCode\":\"test.observe\","
                        + "\"payload\":{\"value\":\"input\"}}",
                "opaque-context"
        );
        return codec.encodeWorkerCommand(new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                deadline,
                codec.encodeDeliverSeed(seed)
        ));
    }

    private static MockResponse response(int code, String body) {
        MockResponse.Builder response = new MockResponse.Builder().code(code);
        if (body != null) {
            response.body(body);
        }
        return response.build();
    }
}
