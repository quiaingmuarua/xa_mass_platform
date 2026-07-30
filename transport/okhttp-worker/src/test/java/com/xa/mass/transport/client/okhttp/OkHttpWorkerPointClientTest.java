package com.xa.mass.transport.client.okhttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OkHttpWorkerPointClientTest {

    private MockWebServer server;
    private OkHttpWorkerPointClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpWorkerPointClient(
                URI.create(server.url("/").toString()),
                "system polling",
                "worker 1",
                Duration.ofSeconds(2)
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    @Test
    void pollBuildsTargetWorkerPathAndHandles204And200()
            throws Exception {
        server.enqueue(response(204, null));
        server.enqueue(response(200, "encoded-command"));

        assertFalse(client.pollCommand().isPresent());
        assertEquals(
                Optional.of("encoded-command"),
                client.pollCommand()
        );

        RecordedRequest first = server.takeRequest(
                1,
                TimeUnit.SECONDS
        );
        assertNotNull(first);
        assertEquals("POST", first.getMethod());
        assertEquals(
                "/api/v1/worker-delivery/endpoint-managers/"
                        + "system%20polling/workers/worker%201/commands:poll",
                first.getTarget()
        );
    }

    @Test
    void submitUsesPointResultPathAndRequires202()
            throws Exception {
        server.enqueue(response(202, ""));

        client.submitResult("encoded-result");

        RecordedRequest request = server.takeRequest(
                1,
                TimeUnit.SECONDS
        );
        assertNotNull(request);
        assertEquals(
                "/api/v1/worker-delivery/endpoint-managers/"
                        + "system%20polling/workers/worker%201/results",
                request.getTarget()
        );
        assertEquals("encoded-result", request.getBody().utf8());

        server.enqueue(response(503, ""));
        WorkerException failure = assertThrows(
                WorkerException.class,
                () -> client.submitResult("encoded-result")
        );
        assertEquals(
                WorkerErrorCode.RESULT_SUBMIT_FAILED,
                failure.errorCode()
        );
    }

    @Test
    void pollUnexpectedStatusHasStableNetworkClassification() {
        server.enqueue(response(503, ""));

        WorkerException failure = assertThrows(
                WorkerException.class,
                client::pollCommand
        );

        assertEquals(
                WorkerErrorCode.COMMAND_POLL_FAILED,
                failure.errorCode()
        );
        assertEquals("polling.pollCommand", failure.operation());
    }

    @Test
    void closeIsIdempotentAndRejectsNewCalls() {
        client.close();
        client.close();

        assertThrows(IllegalStateException.class, client::pollCommand);
    }

    private static MockResponse response(int code, String body) {
        MockResponse.Builder response =
                new MockResponse.Builder().code(code);
        if (body != null) {
            response.body(body);
        }
        return response.build();
    }
}
