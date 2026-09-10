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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only HTTP control for the finite Lab and SMS scenarios. */
final class ScenarioWorkerControlServer implements AutoCloseable {

    static final int DEFAULT_PORT = 18086;

    private static final String LAB_PATH = "/lab";
    private static final String WORKERS_PATH = "/lab/v1/workers";
    private static final String WORKERS_STOP_PATH = WORKERS_PATH + ":stop";
    private static final String CONSOLE_RESOURCE =
            "/com/xa/mass/scenarioworkers/worker-lab.html";
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_STOP_BATCH_SIZE = 100;
    private static final int CONTROL_THREADS = 8;
    private static final String SMS_PATH = "/lab/v1/sms/";

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
        this.scheduledStops = workers.isSms() ? null : Objects.requireNonNull(scheduledStops, "scheduledStops");
        this.server = Objects.requireNonNull(server, "server");
        this.consoleHtml = new String(Objects.requireNonNull(consoleHtml, "consoleHtml"), StandardCharsets.UTF_8)
                .replace("__SCENARIO__", workers.isSms() ? "sms" : "lab").getBytes(StandardCharsets.UTF_8);
        AtomicInteger threadSequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(CONTROL_THREADS, CONTROL_THREADS, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), task -> {
            Thread thread = new Thread(
                    task,
                    "scenario-worker-lab-http-"
                            + threadSequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            server.setExecutor(executor);
            server.createContext(LAB_PATH, this::handleConsole);
            if (workers.isSms()) {
                server.createContext(SMS_PATH, this::handleSms);
            } else {
                server.createContext(WORKERS_PATH, this::handle);
                server.createContext("/lab/v1/execution-witnesses", this::handleWitnesses);
            }
        } catch (RuntimeException | Error failure) {
            executor.shutdownNow();
            throw failure;
        }
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
        try {
            return new ScenarioWorkerControlServer(workers, scheduledStops, server, consoleHtml);
        } catch (RuntimeException | Error failure) {
            // A bound, never-started JDK HttpServer can retain its selector's socket
            // after stop(). Run the dispatcher so shutdown also releases that binding.
            server.start();
            server.stop(0);
            throw failure;
        }
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

    private void handleSms(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath().substring(SMS_PATH.length());
            String method = exchange.getRequestMethod();
            var sms = workers.smsScenario();
            Map<String, String> query = new LinkedHashMap<>();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    String[] parts = pair.split("=", -1);
                    if (parts.length != 2 || !Set.of("offset", "limit").contains(parts[0])
                            || query.putIfAbsent(parts[0], parts[1]) != null)
                        throw new IllegalArgumentException("Invalid SMS page query");
                }
                if (!method.equals("GET") || !(path.equals("inventory") || path.equals("records")))
                    throw new IllegalArgumentException("Query parameters are not supported here");
            }
            Map<String, ?> response;
            if (path.startsWith("workers/")) {
                requireMethod(exchange, "POST");
                String[] coordinate = path.substring("workers/".length()).split("/", -1);
                if (coordinate.length != 2) throw new IllegalArgumentException("Expected Group and replica key");
                int action = coordinate[1].lastIndexOf(':');
                if (action < 1) throw new IllegalArgumentException("Expected start or stop action");
                String group = decodeSegment(coordinate[0]);
                String key = decodeSegment(coordinate[1].substring(0, action));
                switch (coordinate[1].substring(action + 1)) {
                    case "start" -> workers.startWorker(group, key);
                    case "stop" -> workers.stopWorker(group, key);
                    default -> throw new IllegalArgumentException("Expected start or stop action");
                }
                respondJson(exchange, 202, Map.of("acceptedCount", 1));
                return;
            }
            response = switch (method + " " + path) {
                case "GET health" -> workers.smsHealth();
                case "GET inventory" -> sms.registry.inventory(Integer.parseInt(query.getOrDefault("offset", "0")),
                        Integer.parseInt(query.getOrDefault("limit", "100")));
                case "GET records" -> sms.registry.records(Integer.parseInt(query.getOrDefault("offset", "0")),
                        Integer.parseInt(query.getOrDefault("limit", "100")));
                case "GET metrics" -> sms.metrics();
                case "POST sms" -> {
                    var body = readSmsBody(exchange);
                    if (!(body.get("text") instanceof String message)) throw new IllegalArgumentException("Invalid SMS text");
                    yield sms.registry.receive(com.xa.mass.scenarioworkers.sms.ListeningRegistry.string(body, "phone"),
                            com.xa.mass.scenarioworkers.sms.ListeningRegistry.string(body, "smsId"), message);
                }
                case "POST traffic/start" -> sms.startTraffic(readSmsBody(exchange));
                case "POST traffic/stop" -> sms.stopTraffic();
                default -> null;
            };
            if (response == null) respondError(exchange, 404, "route_not_found", "Unknown SMS route");
            else respondJson(exchange, 200, response);
        } catch (ScenarioWorkers.UnknownWorkerException error) {
            respondError(exchange, 404, "worker_not_found", error.getMessage());
        } catch (IllegalArgumentException error) {
            respondError(exchange, 400, "invalid_request", error.getMessage());
        } catch (ResponseSentException ignored) {
            // Response already sent.
        } catch (IllegalStateException error) {
            respondError(exchange, 409, "sms_conflict", error.getMessage());
        } catch (RuntimeException error) {
            respondError(exchange, 500, "sms_unavailable", "SMS operation failed");
        } finally { exchange.close(); }
    }

    private static Map<String, Object> readSmsBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("Request too large");
        return Jsons.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (!started) server.start();
            server.stop(0);
        }
        // Do not join a publishing HTTP Handler before the Host can revoke its
        // Worker runs. Control threads are daemon threads; in-flight calls may fail.
        executor.shutdownNow();
    }

    /** Join only after the Host has revoked Workers, so callbacks cannot delay revocation. */
    void awaitClosed() {
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
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
        } catch (ScenarioWorkerCommandCheckpoints.UnknownCheckpointException
                 error) {
            respondError(
                    exchange,
                    404,
                    "checkpoint_not_found",
                    error.getMessage()
            );
        } catch (ScenarioWorkerCommandCheckpoints.CheckpointConflictException
                 error) {
            respondError(
                    exchange,
                    409,
                    "checkpoint_conflict",
                    error.getMessage()
            );
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

    private void handleWitnesses(HttpExchange exchange) throws IOException {
        try {
            if (!"/lab/v1/execution-witnesses".equals(exchange.getRequestURI().getPath())) {
                respondError(exchange, 404, "not_found", "Unknown Lab path");
                return;
            }
            requireMethod(exchange, "GET");
            Map<String, String> query = new LinkedHashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String part : raw.split("&", -1)) {
                    String[] pair = part.split("=", -1);
                    if (pair.length != 2 || !Set.of("after", "limit").contains(pair[0])
                            || query.putIfAbsent(pair[0], pair[1]) != null) {
                        throw new IllegalArgumentException("Invalid witness query");
                    }
                }
            }
            respondJson(exchange, 200, workers.executionWitnesses(
                    Long.parseLong(query.getOrDefault("after", "0")),
                    Integer.parseInt(query.getOrDefault("limit", "100"))));
        } catch (IllegalArgumentException error) {
            respondError(exchange, 400, "invalid_request", "Invalid witness cursor or limit");
        } catch (ResponseSentException ignored) {
            // Method-specific response already sent.
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
        if (WORKERS_STOP_PATH.equals(path)) {
            requireMethod(exchange, "POST");
            List<ScenarioWorkerCoordinate> targets = requiredStopTargets(
                    readBody(exchange)
            );
            workers.stopWorkers(targets);
            respondJson(
                    exchange,
                    202,
                    Map.of("acceptedCount", targets.size())
            );
            return;
        }
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
                    "Worker route requires WorkerGroup and Lab Worker key"
            );
        }
        String workerGroupId = decodeSegment(
                remainder.substring(0, separator)
        );
        String workerAndAction = remainder.substring(separator + 1);
        Action action = Action.parse(workerAndAction);
        String labWorkerKey = decodeSegment(action.encodedLabWorkerKey());
        handleWorker(
                exchange,
                workerGroupId,
                labWorkerKey,
                action
        );
    }

    private void handleWorker(
            HttpExchange exchange,
            String workerGroupId,
            String labWorkerKey,
            Action action
    ) throws IOException {
        switch (action.kind()) {
            case SNAPSHOT -> {
                String method = exchange.getRequestMethod();
                if ("GET".equals(method)) {
                    respondSnapshot(exchange, 200, workerGroupId,
                            labWorkerKey, true);
                } else if ("PUT".equals(method)) {
                    workers.replaceWorkerState(
                            workerGroupId,
                            labWorkerKey,
                            readBody(exchange)
                    );
                    respondSnapshot(exchange, 200, workerGroupId,
                            labWorkerKey, true);
                } else {
                    methodNotAllowed(exchange, "GET, PUT");
                }
            }
            case START -> {
                requireMethod(exchange, "POST");
                workers.startWorker(workerGroupId, labWorkerKey);
                respondSnapshot(exchange, 202, workerGroupId,
                        labWorkerKey, false);
            }
            case STOP -> {
                requireMethod(exchange, "POST");
                workers.stopWorker(workerGroupId, labWorkerKey);
                respondSnapshot(exchange, 202, workerGroupId,
                        labWorkerKey, false);
            }
            case SCHEDULE_STOP -> {
                requireMethod(exchange, "POST");
                long delayMillis = requiredDelayMillis(readBody(exchange));
                if (!scheduledStops.schedule(
                        workerGroupId,
                        labWorkerKey,
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
                        labWorkerKey, false);
            }
            case CANCEL_SCHEDULED_STOP -> {
                requireMethod(exchange, "DELETE");
                scheduledStops.cancel(workerGroupId, labWorkerKey);
                exchange.sendResponseHeaders(204, -1L);
            }
            case COMMAND_CHECKPOINT -> handleCommandCheckpoint(
                    exchange,
                    workerGroupId,
                    labWorkerKey
            );
            case PROPERTIES -> {
                String method = exchange.getRequestMethod();
                if (!"PATCH".equals(method) && !"PUT".equals(method)) {
                    methodNotAllowed(exchange, "PATCH, PUT");
                    return;
                }
                Map<String, String> properties = new LinkedHashMap<>();
                Jsons.parseObject(readBody(exchange)).forEach((key, value) -> {
                    if (!(value instanceof String text)) {
                        throw new IllegalArgumentException("Properties values must be strings");
                    }
                    properties.put(key, text);
                });
                boolean accepted = workers.publishProperties(
                        workerGroupId, labWorkerKey, properties, "PUT".equals(method)
                );
                respondJson(exchange, 200, Map.of("persisted", true, "sendAccepted", accepted));
            }
        }
    }

    private void handleCommandCheckpoint(
            HttpExchange exchange,
            String workerGroupId,
            String labWorkerKey
    ) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> respondJson(
                    exchange,
                    200,
                    encodeCheckpoint(workers.commandCheckpoint(
                            workerGroupId,
                            labWorkerKey
                    ))
            );
            case "PUT" -> {
                CheckpointRequest request = requiredCheckpointRequest(
                        readBody(exchange)
                );
                workers.armCommandCheckpoint(
                        workerGroupId,
                        labWorkerKey,
                        request.checkpointToken(),
                        request.maximumHoldMillis()
                );
                respondJson(
                        exchange,
                        201,
                        encodeCheckpoint(workers.commandCheckpoint(
                                workerGroupId,
                                labWorkerKey
                        ))
                );
            }
            case "DELETE" -> {
                workers.releaseCommandCheckpoint(
                        workerGroupId,
                        labWorkerKey
                );
                exchange.sendResponseHeaders(204, -1L);
            }
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private void respondSnapshot(
            HttpExchange exchange,
            int status,
            String workerGroupId,
            String labWorkerKey,
            boolean includeProperties
    ) throws IOException {
        respondJson(
                exchange,
                status,
                encodeSnapshot(workers.workerSnapshot(
                        workerGroupId,
                        labWorkerKey,
                        includeProperties
                ))
        );
    }

    private Map<String, Object> encodeSnapshot(
            ScenarioWorkers.WorkerControlSnapshot snapshot
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workerGroupId", snapshot.workerGroupId());
        value.put("labWorkerKey", snapshot.labWorkerKey());
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
                        snapshot.labWorkerKey()
                )
        );
        if (snapshot.workerProperties() != null) {
            value.put("workerProperties", snapshot.workerProperties());
        }
        return value;
    }

    private static Map<String, Object> encodeCheckpoint(
            ScenarioWorkerCommandCheckpoints.Snapshot snapshot
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workerGroupId", snapshot.worker().workerGroupId());
        value.put("labWorkerKey", snapshot.worker().labWorkerKey());
        value.put("checkpointToken", snapshot.checkpointToken());
        value.put("maximumHoldMillis", snapshot.maximumHoldMillis());
        value.put("state", snapshot.state().name());
        value.put("enteredAtEpochMillis", snapshot.enteredAtEpochMillis());
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

    private static CheckpointRequest requiredCheckpointRequest(
            String encoded
    ) {
        Map<String, Object> value = Jsons.parseObject(encoded);
        if (!value.keySet().equals(java.util.Set.of(
                "checkpointToken",
                "maximumHoldMillis"
        ))
                || !(value.get("checkpointToken") instanceof String token)
                || token.isBlank()
                || !(value.get("maximumHoldMillis") instanceof Long hold)) {
            throw new IllegalArgumentException(
                    "command checkpoint requires checkpointToken and "
                            + "integer maximumHoldMillis"
            );
        }
        return new CheckpointRequest(token, hold);
    }

    private static List<ScenarioWorkerCoordinate> requiredStopTargets(
            String encoded
    ) {
        List<Object> values = Jsons.parseArray(encoded);
        if (values.isEmpty() || values.size() > MAX_STOP_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "worker stop batch must contain 1..100 coordinates"
            );
        }
        List<ScenarioWorkerCoordinate> targets = new ArrayList<>(
                values.size()
        );
        Set<ScenarioWorkerCoordinate> unique = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> coordinate)
                    || !coordinate.keySet().equals(Set.of(
                            "workerGroupId",
                            "labWorkerKey"
                    ))
                    || !(coordinate.get("workerGroupId")
                            instanceof String workerGroupId)
                    || !(coordinate.get("labWorkerKey")
                            instanceof String labWorkerKey)) {
                throw new IllegalArgumentException(
                        "worker stop coordinate requires workerGroupId and "
                                + "labWorkerKey"
                );
            }
            ScenarioWorkerCoordinate target = new ScenarioWorkerCoordinate(
                    workerGroupId,
                    labWorkerKey
            );
            if (!unique.add(target)) {
                throw new IllegalArgumentException(
                        "worker stop batch contains duplicate coordinates"
                );
            }
            targets.add(target);
        }
        return List.copyOf(targets);
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
        CANCEL_SCHEDULED_STOP,
        COMMAND_CHECKPOINT,
        PROPERTIES
    }

    private record Action(
            String encodedLabWorkerKey,
            ActionKind kind
    ) {

        private static Action parse(String value) {
            for (Map.Entry<String, ActionKind> suffix : Map.of(
                    ":start", ActionKind.START,
                    ":stop", ActionKind.STOP,
                    ":schedule-stop", ActionKind.SCHEDULE_STOP,
                    ":scheduled-stop", ActionKind.CANCEL_SCHEDULED_STOP,
                    ":command-checkpoint", ActionKind.COMMAND_CHECKPOINT,
                    ":properties", ActionKind.PROPERTIES
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

    private record CheckpointRequest(
            String checkpointToken,
            long maximumHoldMillis
    ) {
    }

    private static final class ResponseSentException
            extends IllegalStateException {
    }
}
