package com.xa.mass.client.http;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.http.exception.MassApiException;
import com.xa.mass.client.http.exception.MassHttpException;
import com.xa.mass.client.http.exception.MassProtocolException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MassHttpClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unwrapsSuccessfulApiResponseAndSendsApiKeyHeader() throws Exception {
        AtomicReference<String> observedApiKey = new AtomicReference<>();
        startServer(exchange -> {
            observedApiKey.set(exchange.getRequestHeaders().getFirst(MassHttpClient.MASS_API_KEY_HEADER));
            respond(exchange, 200, "{\"code\":0,\"msg\":\"ok\",\"data\":{\"value\":\"ready\"}}");
        });

        TestData data = platform("mass_sk_success").http().get("/ok", TestData.class);

        assertEquals("ready", data.value());
        assertEquals("mass_sk_success", observedApiKey.get());
    }

    @Test
    void nonZeroApiCodeThrowsApiException() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"code\":403,\"msg\":\"denied\",\"data\":null}"));

        MassApiException error = assertThrows(MassApiException.class,
                () -> platform("mass_sk_denied").http().get("/denied", TestData.class));

        assertEquals(403, error.apiCode());
        assertEquals("denied", error.apiMessage());
        assertEquals("/denied", error.path());
    }

    @Test
    void non2xxStatusThrowsHttpExceptionAndRedactsAuthToken() throws Exception {
        startServer(exchange -> respond(exchange, 500, "token mass_sk_secret should not leak"));

        MassHttpException error = assertThrows(MassHttpException.class,
                () -> platform("mass_sk_secret").http().get("/fail", TestData.class));

        assertEquals(500, error.statusCode());
        assertFalse(error.getMessage().contains("mass_sk_secret"));
        assertFalse(error.responseBody().contains("mass_sk_secret"));
    }

    @Test
    void invalidEnvelopeThrowsProtocolException() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"msg\":\"missing-code\"}"));

        assertThrows(MassProtocolException.class,
                () -> platform("mass_sk_protocol").http().get("/bad-envelope", TestData.class));
    }

    private MassPlatform platform(String apiKey) {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey(apiKey)
                .build();
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record TestData(String value) {
    }
}
