package com.xa.mass.workerdelivery.adapter.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.adapter.http.WorkerDeliveryHttpClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Test-only HTTP peer. It deliberately exposes no Adapter owner ports. */
public final class ScriptedHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    public ScriptedHttpServer(Handler handler) {
        try {
            server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not create test HTTP server",
                    error
            );
        }
        this.handler = Objects.requireNonNull(handler, "handler");
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public WorkerDeliveryHttpClient client() {
        return client(Duration.ofSeconds(2));
    }

    public WorkerDeliveryHttpClient client(Duration timeout) {
        return new WorkerDeliveryHttpClient(
                URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort()
                ),
                timeout
        );
    }

    public void handler(Handler value) {
        handler = Objects.requireNonNull(value, "handler");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Request request = new Request(
                exchange.getRequestURI().getRawPath(),
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                )
        );
        requests.add(request);
        Response response;
        try {
            response = Objects.requireNonNull(
                    handler.handle(request),
                    "handler response"
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            response = new Response(503, "{}");
        } catch (Exception error) {
            response = new Response(500, "{}");
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(
                response.statusCode(),
                response.statusCode() == 204 ? -1 : body.length
        );
        if (response.statusCode() != 204) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @FunctionalInterface
    public interface Handler {

        Response handle(Request request) throws Exception;
    }

    public record Request(String rawPath, String body) {
    }

    public record Response(int statusCode, String body) {

        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("invalid HTTP status");
            }
            body = body == null ? "" : body;
        }
    }
}
