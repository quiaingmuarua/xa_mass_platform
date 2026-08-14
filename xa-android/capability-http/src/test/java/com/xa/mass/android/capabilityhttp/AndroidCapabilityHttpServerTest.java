package com.xa.mass.android.capabilityhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class AndroidCapabilityHttpServerTest {

    private AndroidCapabilityHttpServer server;

    @After
    public void closeServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void exposesHealthEventsAndExecutesOneHandler()
            throws Exception {
        AtomicInteger executions = new AtomicInteger();
        server = start(Arrays.asList(
                definition("demo.echo", executions),
                WorkerEventDefinition.of(
                        "TASK",
                        "demo.second",
                        WorkerEventParameterResolvers.jsonMap(),
                        ignored -> "plain-text"
                )
        ));

        HttpResult health = request("GET", "/health", null, null);
        assertEquals(200, health.status);
        assertEquals("ok", health.body.get("status"));

        HttpResult events = request("GET", "/events", null, null);
        assertEquals(200, events.status);
        assertEquals(
                Arrays.asList("demo.echo", "demo.second"),
                events.body.get("events")
        );

        HttpResult call = request(
                "POST",
                "/events/demo.echo:call",
                "application/json; charset=utf-8",
                "{\"value\":\"hello\"}"
        );
        assertEquals(200, call.status);
        assertEquals("succeeded", call.body.get("status"));
        assertEquals("demo.echo", call.body.get("eventCode"));
        assertEquals("200", call.body.get("outcomeCode"));
        assertEquals(
                Collections.singletonMap("value", "hello"),
                call.body.get("result")
        );
        assertEquals(1, executions.get());

        HttpResult plain = request(
                "POST",
                "/events/demo.second:call",
                "application/json",
                "{}"
        );
        assertEquals(200, plain.status);
        assertEquals("plain-text", plain.body.get("result"));
    }

    @Test
    public void mapsInputMissingAndExecutionFailures()
            throws Exception {
        WorkerEventDefinition<Map<String, Object>> failing =
                WorkerEventDefinition.of(
                        "TASK",
                        "demo.fail",
                        WorkerEventParameterResolvers.jsonMap(),
                        ignored -> {
                            throw new IllegalStateException("boom");
                        }
                );
        server = start(Collections.singletonList(failing));

        assertFailure(
                request(
                        "POST",
                        "/events/demo.fail:call",
                        "application/json",
                        "[]"
                ),
                400,
                "3301"
        );
        assertFailure(
                request(
                        "POST",
                        "/events/demo.missing:call",
                        "application/json",
                        "{}"
                ),
                404,
                "3302"
        );
        assertFailure(
                request(
                        "POST",
                        "/events/demo.fail:call",
                        "application/json",
                        "{}"
                ),
                500,
                "3303"
        );
    }

    @Test
    public void enforcesHttpProtocolAndOwnsIdempotentLoopbackLifecycle()
            throws Exception {
        server = AndroidCapabilityHttpServer.create(
                0,
                Collections.singletonList(
                        definition("demo.echo", new AtomicInteger())
                )
        );
        server.start();
        server.start();

        assertTrue(server.isRunning());
        assertEquals("127.0.0.1", server.endpoint().getHost());
        assertEquals(
                415,
                request(
                        "POST",
                        "/events/demo.echo:call",
                        "text/plain",
                        "{}"
                ).status
        );
        assertEquals(
                405,
                request("GET", "/events/demo.echo:call", null, null)
                        .status
        );
        assertEquals(
                404,
                request("GET", "/routes", null, null).status
        );

        server.close();
        server.close();
        assertFalse(server.isRunning());
    }

    @Test
    public void rejectsNonTaskAndDuplicateDefinitions() {
        WorkerEventDefinition<Map<String, Object>> system =
                WorkerEventDefinition.of(
                        "SYSTEM",
                        "demo.system",
                        WorkerEventParameterResolvers.jsonMap(),
                        Jsons::toJson
                );
        assertThrows(
                IllegalArgumentException.class,
                () -> AndroidCapabilityHttpServer.create(
                        Collections.singletonList(system)
                )
        );

        WorkerEventDefinition<Map<String, Object>> first = definition(
                "demo.duplicate",
                new AtomicInteger()
        );
        WorkerEventDefinition<Map<String, Object>> second = definition(
                "demo.duplicate",
                new AtomicInteger()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> AndroidCapabilityHttpServer.create(
                        Arrays.asList(first, second)
                )
        );
    }

    private AndroidCapabilityHttpServer start(
            Collection<? extends WorkerEventDefinition<?>> definitions
    ) throws IOException {
        AndroidCapabilityHttpServer created =
                AndroidCapabilityHttpServer.create(0, definitions);
        created.start();
        return created;
    }

    private static WorkerEventDefinition<Map<String, Object>> definition(
            String eventCode,
            AtomicInteger executions
    ) {
        return WorkerEventDefinition.of(
                "TASK",
                eventCode,
                WorkerEventParameterResolvers.jsonMap(),
                payload -> {
                    executions.incrementAndGet();
                    return Jsons.toJson(payload);
                }
        );
    }

    private HttpResult request(
            String method,
            String path,
            String contentType,
            String body
    ) throws IOException {
        URI uri = server.endpoint().resolve(path);
        HttpURLConnection connection = (HttpURLConnection)
                uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setRequestMethod(method);
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            connection.setDoOutput(true);
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String response;
        try (InputStream input = stream) {
            response = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } finally {
            connection.disconnect();
        }
        try {
            return new HttpResult(status, Jsons.parseObject(response));
        } catch (IllegalArgumentException error) {
            throw new AssertionError(
                    "Invalid JSON response: status="
                            + status
                            + ", body="
                            + response,
                    error
            );
        }
    }

    private static void assertFailure(
            HttpResult result,
            int status,
            String outcomeCode
    ) {
        assertEquals(status, result.status);
        assertEquals("failed", result.body.get("status"));
        assertEquals(outcomeCode, result.body.get("outcomeCode"));
    }

    private static final class HttpResult {

        private final int status;
        private final Map<String, Object> body;

        private HttpResult(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }
    }
}
