package com.xa.mass.worker.transport.polling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint.WORKER;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerCommandExecutor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<String> executedCommand =
            new AtomicReference<>();
    private final AtomicBoolean dropCommand = new AtomicBoolean();
    private MockWebServer server;
    private PollingWorkerTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
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
            if (dropCommand.get()) {
                return Optional.empty();
            }
            return Optional.of(result());
        };
        transport = new PollingWorkerTransport(
                URI.create(server.url("/").toString()),
                "system polling",
                WORKER_ID,
                Duration.ofSeconds(2),
                executor
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
        WorkerResult result = codec.decodeWorkerResult(
                submitted.getBody().utf8()
        );
        assertNotNull(result);
        assertEquals("200", result.outcomeCode());
        assertEquals(
                "{\"observed\":\"input\"}",
                result.payload()
        );
        assertEquals("opaque-context", result.forward());
        assertEquals(TASK, result.dst());
        assertEquals(command(), executedCommand.get());
        assertFalse(transport.hasPendingResult());
    }

    @Test
    void pendingResultRetriesBeforeAnotherPoll() throws Exception {
        server.enqueue(response(200, command()));
        server.enqueue(response(503, ""));

        WorkerException failure = assertThrows(
                WorkerException.class,
                transport::runOnce
        );
        assertEquals(
                WorkerErrorCode.RESULT_SUBMIT_FAILED,
                failure.errorCode()
        );
        assertEquals("polling.submitResult", failure.operation());
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
    void pollAndMalformedResponseExposeStableErrorCodes()
            throws Exception {
        server.enqueue(response(503, ""));

        WorkerException pollFailure = assertThrows(
                WorkerException.class,
                transport::runOnce
        );
        assertEquals(
                WorkerErrorCode.COMMAND_POLL_FAILED,
                pollFailure.errorCode()
        );
        assertEquals("polling.pollCommand", pollFailure.operation());

        server.enqueue(response(200, "{bad-json"));
        WorkerException invalidResponse = assertThrows(
                WorkerException.class,
                transport::runOnce
        );
        assertEquals(
                WorkerErrorCode.COMMAND_MESSAGE_INVALID,
                invalidResponse.errorCode()
        );
        assertEquals("command.decode", invalidResponse.operation());
    }

    @Test
    void emptyExecutionIsDroppedWithoutResult() throws Exception {
        dropCommand.set(true);
        server.enqueue(response(200, command()));

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
        return command(4_102_444_800_000L);
    }

    private String command(long deadline) {
        return codec.encodeWorkerCommand(new WorkerCommand(
                COMMAND_ID,
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{\"value\":\"input\"}",
                "opaque-context"
        ));
    }

    private static WorkerResult result() {
        return new WorkerResult(
                COMMAND_ID,
                TASK,
                "test.observe",
                "200",
                "{\"observed\":\"input\"}",
                "opaque-context"
        );
    }

    private static MockResponse response(int code, String body) {
        MockResponse.Builder response = new MockResponse.Builder().code(code);
        if (body != null) {
            response.body(body);
        }
        return response.build();
    }
}
