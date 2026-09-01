package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AndroidDeviceHostClientTest {

    private HttpServer server;
    private AndroidDeviceHostClient client;
    private boolean invalidHealthJson;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new AndroidDeviceHostClient(new JsonHttpClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2L)
        ));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsHealthEventsSnapshotAndLifecycleResponses() {
        client.requireHealth();
        assertFalse(client.isUnavailable());
        assertEquals(
                AndroidWorkerProofConstants.DEVICE_EVENTS,
                client.events()
        );
        AndroidDeviceHostClient.Snapshot snapshot = client.snapshot();
        assertEquals("RUNNING", snapshot.state());
        assertEquals("worker-1", snapshot.workerId());
        assertEquals(1L, snapshot.activeDelayCount());

        client.stop();
        client.start();
    }

    @Test
    void distinguishesAStoppedLocalHostFromAHealthyHost() {
        server.stop(0);

        assertTrue(client.isUnavailable());
    }

    @Test
    void doesNotTreatInvalidHealthJsonAsHostUnavailability() {
        invalidHealthJson = true;

        ProofFailure failure = assertThrows(
                ProofFailure.class,
                client::isUnavailable
        );

        assertEquals("androidDevice.health.json", failure.invariant());
    }

    @Test
    void rejectsIdentityDriftWithoutPollingUntilTimeout() {
        ProofFailure failure = assertThrows(ProofFailure.class, () ->
                AndroidWorkerProofAssertions.awaitRunning(
                        client,
                        Duration.ofSeconds(1L),
                        "worker-2"
                ));

        assertEquals("device.lifecycle.identity", failure.invariant());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/health".equals(path) && invalidHealthJson) {
            byte[] body = "{".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        Map<String, Object> response;
        if ("/health".equals(path)) {
            response = Map.of("status", "ok");
        } else if ("/events".equals(path)) {
            response = Map.of(
                    "events",
                    new ArrayList<>(AndroidWorkerProofConstants.DEVICE_EVENTS)
            );
        } else {
            String eventName = path.substring(
                    "/events/".length(),
                    path.length() - ":call".length()
            );
            Object result;
            if (AndroidWorkerProofConstants.HOST_SNAPSHOT_EVENT.equals(
                    eventName
            )) {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("state", "RUNNING");
                snapshot.put("workerId", "worker-1");
                snapshot.put("endpointUri", "ws://127.0.0.1/worker");
                snapshot.put("diagnosticMessage", null);
                snapshot.put("processedCommands", 3L);
                snapshot.put("lastEvent", null);
                snapshot.put("activeDelayCount", 1L);
                result = snapshot;
            } else {
                result = Map.of(
                        "accepted",
                        true,
                        "requestedState",
                        AndroidWorkerProofConstants.HOST_START_EVENT.equals(
                                eventName
                        ) ? "RUNNING" : "STOPPED"
                );
            }
            response = Map.of(
                    "status", "succeeded",
                    "eventCode", eventName,
                    "outcomeCode", "200",
                    "result", result
            );
        }
        byte[] body = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
