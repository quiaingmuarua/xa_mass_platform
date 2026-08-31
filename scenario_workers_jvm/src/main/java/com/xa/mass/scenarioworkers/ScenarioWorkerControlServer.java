package com.xa.mass.scenarioworkers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loopback-only HTTP adapter for the Scenario Worker Lab. */
final class ScenarioWorkerControlServer implements AutoCloseable {

    static final int DEFAULT_PORT = 18086;

    private static final String LAB_PATH = "/lab";
    private static final String WORKERS_PATH = "/lab/v1/workers";
    private static final String CONSOLE_RESOURCE =
            "/com/xa/mass/scenarioworkers/worker-lab.html";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final ScenarioWorkers workers;
    private final ScenarioWorkerScheduledStops scheduledStops;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] consoleHtml;

    private boolean started;
    private boolean closed;

    private ScenarioWorkerControlServer(
            ScenarioWorkers workers,
            ScenarioWorkerScheduledStops scheduledStops,
            HttpServer server,
            byte[] consoleHtml
    ) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.scheduledStops = Objects.requireNonNull(
                scheduledStops,
                "scheduledStops"
        );
        this.server = Objects.requireNonNull(server, "server");
        this.consoleHtml = Objects.requireNonNull(
                consoleHtml,
                "consoleHtml"
        ).clone();
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "scenario-worker-lab-http"
            );
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(LAB_PATH, this::handleConsole);
        server.createContext(WORKERS_PATH, this::handle);
    }

    static ScenarioWorkerControlServer open(
            int port,
            ScenarioWorkers workers,
            ScenarioWorkerScheduledStops scheduledStops
    ) throws IOException {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "control-port must be between 0 and 65535"
            );
        }
        byte[] consoleHtml = loadConsoleHtml();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(
                        InetAddress.getLoopbackAddress(),
                        port
                ),
                0
        );
        return new ScenarioWorkerControlServer(
                workers,
                scheduledStops,
                server,
                consoleHtml
        );
    }

    synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario Worker control server is closed"
            );
        }
        if (started) {
            return;
        }
        server.start();
        started = true;
    }

    URI baseUri() {
        InetSocketAddress address = server.getAddress();
        return URI.create(
                "http://127.0.0.1:" + address.getPort()
        );
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            server.stop(0);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getRawQuery() != null) {
                respondError(exchange, 400, "invalid_request",
                        "Query parameters are not supported");
                return;
            }
            route(exchange);
        } catch (ScenarioWorkers.UnknownWorkerException error) {
            respondError(exchange, 404, "worker_not_found", error.getMessage());
        } catch (ScenarioWorkerAssemblyException error) {
            int status = error.errorCode() == 14013 ? 400 : 500;
            respondError(
                    exchange,
                    status,
                    status == 400 ? "invalid_worker_state" : "lab_unavailable",
                    error.getMessage()
            );
        } catch (IllegalArgumentException error) {
            respondError(exchange, 400, "invalid_request", error.getMessage());
        } catch (ResponseSentException ignored) {
            // The method-specific response has already been written.
        } catch (IllegalStateException error) {
            respondError(exchange, 409, "state_conflict", error.getMessage());
        } catch (RuntimeException error) {
            respondError(
                    exchange,
                    500,
                    "lab_failure",
                    "Scenario Worker Lab operation failed"
            );
        } finally {
            exchange.close();
        }
    }

    private void handleConsole(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getRawQuery() != null) {
                respondError(exchange, 400, "invalid_request",
                        "Query parameters are not supported");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (!LAB_PATH.equals(path) && !(LAB_PATH + "/").equals(path)) {
                respondError(
                        exchange,
                        404,
                        "route_not_found",
                        "Unknown Lab route"
                );
                return;
            }
            requireMethod(exchange, "GET");
            respondHtml(exchange, consoleHtml);
        } catch (ResponseSentException ignored) {
            // The method-specific response has already been written.
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (WORKERS_PATH.equals(path)) {
            requireMethod(exchange, "GET");
            List<Map<String, Object>> encoded = new ArrayList<>();
            for (ScenarioWorkers.WorkerControlSnapshot snapshot
                    : workers.workerSnapshots()) {
                encoded.add(encodeSnapshot(snapshot));
            }
            respondJson(exchange, 200, Map.of("workers", encoded));
            return;
        }
        String prefix = WORKERS_PATH + "/";
        if (!path.startsWith(prefix)) {
            respondError(exchange, 404, "route_not_found", "Unknown Lab route");
            return;
        }
        String remainder = path.substring(prefix.length());
        int separator = remainder.indexOf('/');
        if (separator <= 0
                || separator == remainder.length() - 1
                || remainder.indexOf('/', separator + 1) >= 0) {
            throw new IllegalArgumentException(
                    "Worker route requires WorkerGroup and client key"
            );
        }
        String workerGroupId = decodeSegment(
                remainder.substring(0, separator)
        );
        String workerAndAction = remainder.substring(separator + 1);
        Action action = Action.parse(workerAndAction);
        String clientWorkerKey = decodeSegment(action.encodedClientWorkerKey());
        handleWorker(
                exchange,
                workerGroupId,
                clientWorkerKey,
                action
        );
    }

    private void handleWorker(
            HttpExchange exchange,
            String workerGroupId,
            String clientWorkerKey,
            Action action
    ) throws IOException {
        switch (action.kind()) {
            case SNAPSHOT -> {
                String method = exchange.getRequestMethod();
                if ("GET".equals(method)) {
                    respondSnapshot(exchange, 200, workerGroupId,
                            clientWorkerKey, true);
                } else if ("PUT".equals(method)) {
                    workers.replaceWorkerState(
                            workerGroupId,
                            clientWorkerKey,
                            readBody(exchange)
                    );
                    respondSnapshot(exchange, 200, workerGroupId,
                            clientWorkerKey, true);
                } else {
                    methodNotAllowed(exchange, "GET, PUT");
                }
            }
            case START -> {
                requireMethod(exchange, "POST");
                workers.startWorker(workerGroupId, clientWorkerKey);
                respondSnapshot(exchange, 202, workerGroupId,
                        clientWorkerKey, false);
            }
            case STOP -> {
                requireMethod(exchange, "POST");
                workers.stopWorker(workerGroupId, clientWorkerKey);
                respondSnapshot(exchange, 202, workerGroupId,
                        clientWorkerKey, false);
            }
            case SCHEDULE_STOP -> {
                requireMethod(exchange, "POST");
                long delayMillis = requiredDelayMillis(readBody(exchange));
                if (!scheduledStops.schedule(
                        workerGroupId,
                        clientWorkerKey,
                        delayMillis
                )) {
                    respondError(
                            exchange,
                            409,
                            "scheduled_stop_exists",
                            "Worker already has a scheduled stop"
                    );
                    return;
                }
                respondSnapshot(exchange, 202, workerGroupId,
                        clientWorkerKey, false);
            }
            case CANCEL_SCHEDULED_STOP -> {
                requireMethod(exchange, "DELETE");
                scheduledStops.cancel(workerGroupId, clientWorkerKey);
                exchange.sendResponseHeaders(204, -1L);
            }
        }
    }

    private void respondSnapshot(
            HttpExchange exchange,
            int status,
            String workerGroupId,
            String clientWorkerKey,
            boolean includeProperties
    ) throws IOException {
        respondJson(
                exchange,
                status,
                encodeSnapshot(workers.workerSnapshot(
                        workerGroupId,
                        clientWorkerKey,
                        includeProperties
                ))
        );
    }

    private Map<String, Object> encodeSnapshot(
            ScenarioWorkers.WorkerControlSnapshot snapshot
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workerGroupId", snapshot.workerGroupId());
        value.put("clientWorkerKey", snapshot.clientWorkerKey());
        value.put(
                "desiredState",
                snapshot.desiredRunning() ? "RUNNING" : "STOPPED"
        );
        value.put("runtimeState", snapshot.runtime().state().name());
        value.put("workerId", snapshot.runtime().workerId());
        value.put(
                "diagnosticMessage",
                snapshot.runtime().diagnosticMessage()
        );
        value.put(
                "scheduledStopAtEpochMillis",
                scheduledStops.scheduledStopAtEpochMillis(
                        snapshot.workerGroupId(),
                        snapshot.clientWorkerKey()
                )
        );
        if (snapshot.workerProperties() != null) {
            value.put("workerProperties", snapshot.workerProperties());
        }
        return value;
    }

    private static long requiredDelayMillis(String encoded) {
        Map<String, Object> value = Jsons.parseObject(encoded);
        if (!value.keySet().equals(java.util.Set.of("delayMillis"))
                || !(value.get("delayMillis") instanceof Long)) {
            throw new IllegalArgumentException(
                    "schedule-stop requires one integer delayMillis"
            );
        }
        return (Long) value.get("delayMillis");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new IllegalArgumentException(
                            "Lab request body exceeds 65536 bytes"
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String decodeSegment(String value) {
        String decoded = URLDecoder.decode(
                value.replace("+", "%2B"),
                StandardCharsets.UTF_8
        );
        if (decoded.isBlank()
                || decoded.contains("/")
                || decoded.equals(".")
                || decoded.equals("..")) {
            throw new IllegalArgumentException(
                    "Worker coordinates must be one non-blank path segment"
            );
        }
        return decoded;
    }

    private static void requireMethod(
            HttpExchange exchange,
            String expected
    ) throws IOException {
        if (!expected.equals(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, expected);
            throw new ResponseSentException();
        }
    }

    private static void methodNotAllowed(
            HttpExchange exchange,
            String allow
    ) throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        respondError(
                exchange,
                405,
                "method_not_allowed",
                "HTTP method is not allowed"
        );
    }

    private static void respondError(
            HttpExchange exchange,
            int status,
            String error,
            String message
    ) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("error", error);
        value.put("message", message == null ? "Lab operation failed" : message);
        respondJson(exchange, status, value);
    }

    private static void respondJson(
            HttpExchange exchange,
            int status,
            Map<String, ?> value
    ) throws IOException {
        byte[] encoded = Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
    }

    private static void respondHtml(
            HttpExchange exchange,
            byte[] encoded
    ) throws IOException {
        exchange.getResponseHeaders().set(
                "Content-Type",
                "text/html; charset=utf-8"
        );
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set(
                "X-Content-Type-Options",
                "nosniff"
        );
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'none'; connect-src 'self'; "
                        + "script-src 'unsafe-inline'; "
                        + "style-src 'unsafe-inline'; "
                        + "img-src 'self' data:; base-uri 'none'; "
                        + "form-action 'none'; frame-ancestors 'none'"
        );
        exchange.sendResponseHeaders(200, encoded.length);
        exchange.getResponseBody().write(encoded);
    }

    private static byte[] loadConsoleHtml() throws IOException {
        try (InputStream input = ScenarioWorkerControlServer.class
                .getResourceAsStream(CONSOLE_RESOURCE)) {
            if (input == null) {
                throw new IOException(
                        "Scenario Worker Lab console resource is missing"
                );
            }
            return input.readAllBytes();
        }
    }

    private enum ActionKind {
        SNAPSHOT,
        START,
        STOP,
        SCHEDULE_STOP,
        CANCEL_SCHEDULED_STOP
    }

    private record Action(
            String encodedClientWorkerKey,
            ActionKind kind
    ) {

        private static Action parse(String value) {
            for (Map.Entry<String, ActionKind> suffix : Map.of(
                    ":start", ActionKind.START,
                    ":stop", ActionKind.STOP,
                    ":schedule-stop", ActionKind.SCHEDULE_STOP,
                    ":scheduled-stop", ActionKind.CANCEL_SCHEDULED_STOP
            ).entrySet()) {
                if (value.endsWith(suffix.getKey())) {
                    return new Action(
                            value.substring(
                                    0,
                                    value.length() - suffix.getKey().length()
                            ),
                            suffix.getValue()
                    );
                }
            }
            return new Action(value, ActionKind.SNAPSHOT);
        }
    }

    private static final class ResponseSentException
            extends IllegalStateException {
    }
}
