package com.xa.mass.samples.polling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PollingWorkerMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String workerId;
    private final String workerKey;
    private final String workerGroupId;
    private final String project;
    private final String eventCode;
    private final String workerContextId;
    private final String region;
    private final String runtime;
    private final String[] routingTags;
    private final long pollIntervalMs;
    private final long heartbeatIntervalMs;
    private final boolean registerContext;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private PollingWorkerMain() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUrl = normalizeBaseUrl(env("MASS_BASE_URL", "http://127.0.0.1:8088"));
        this.workerId = requiredEnv("MASS_WORKER_ID", "java-worker-api-001");
        this.workerKey = requiredEnv("MASS_WORKER_KEY", "java-worker-key");
        this.workerGroupId = env("MASS_WORKER_GROUP_ID", "java-runtime");
        this.project = env("MASS_PROJECT", "crawlerApp");
        this.eventCode = env("MASS_EVENT_CODE", "crawler.fetch-page");
        this.workerContextId = env("MASS_WORKER_CONTEXT_ID", "ctx-" + workerId);
        this.region = env("MASS_REGION", "us");
        this.runtime = "java-" + System.getProperty("java.version");
        this.routingTags = splitCsv(env("MASS_ROUTING_TAGS", "web," + region));
        this.pollIntervalMs = longEnv("MASS_POLL_INTERVAL_MS", 1000L);
        this.heartbeatIntervalMs = longEnv("MASS_HEARTBEAT_INTERVAL_MS", 10000L);
        this.registerContext = booleanEnv("MASS_REGISTER_CONTEXT", true);
    }

    public static void main(String[] args) throws Exception {
        new PollingWorkerMain().run();
    }

    private void run() throws Exception {
        log("starting polling worker " + workerId + " for " + eventCode + " at " + baseUrl);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> safeShutdown("java-shutdown-hook"),
                "java-polling-worker-shutdown"));

        registerWorker();
        if (registerContext) {
            registerWorkerContext();
        }
        post("/worker-api/v1/workers/" + encoded(workerId) + ":online",
                jsonObject("reason", "java-worker-online"));

        long lastHeartbeatAt = 0L;
        while (!shuttingDown.get()) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatAt >= heartbeatIntervalMs) {
                post("/worker-api/v1/workers/" + encoded(workerId) + ":heartbeat",
                        jsonObject("reason", "java-worker-heartbeat"));
                lastHeartbeatAt = now;
            }
            pollOnce();
            Thread.sleep(pollIntervalMs);
        }
    }

    private void registerWorker() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("workerId", workerId);
        body.addProperty("workerGroupId", workerGroupId);
        body.addProperty("transportHint", "polling");

        JsonObject attributes = new JsonObject();
        attributes.addProperty("lang", "java");
        attributes.addProperty("runtime", runtime);
        attributes.addProperty("region", region);
        body.add("attributes", attributes);

        JsonArray eventBindings = new JsonArray();
        JsonObject binding = new JsonObject();
        binding.addProperty("eventCode", eventCode);
        JsonArray projectCodes = new JsonArray();
        projectCodes.add(project);
        binding.add("projectCodes", projectCodes);
        eventBindings.add(binding);
        body.add("eventBindings", eventBindings);

        JsonObject response = post("/worker-api/v1/workers", body);
        log("registered worker: " + response.get("data"));
    }

    private void registerWorkerContext() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("workerContextId", workerContextId);
        body.addProperty("workerId", workerId);
        body.addProperty("project", project);
        JsonArray tags = new JsonArray();
        for (String routingTag : routingTags) {
            tags.add(routingTag);
        }
        body.add("routingTags", tags);
        JsonObject attributes = new JsonObject();
        attributes.addProperty("region", region);
        attributes.addProperty("runtime", "java");
        body.add("attributes", attributes);

        JsonObject response = post("/worker-api/v1/workers/" + encoded(workerId) + "/contexts", body);
        log("registered worker context: " + response.get("data"));
    }

    private void pollOnce() throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("maxMessages", 10);
        JsonObject response = post("/worker-api/v1/workers/" + encoded(workerId) + ":poll", request);
        JsonObject data = objectMember(response, "data");
        JsonArray items = arrayMember(data, "items");
        for (JsonElement element : items) {
            if (element != null && element.isJsonObject()) {
                handleDispatch(element.getAsJsonObject());
            }
        }
    }

    private void handleDispatch(JsonObject item) throws Exception {
        String taskId = stringMember(item, "taskId");
        String messageId = stringMember(item, "messageId");
        String dispatchEventCode = stringMember(item, "eventCode");
        log("received taskId=" + taskId + " messageId=" + messageId + " eventCode=" + dispatchEventCode);

        JsonObject result;
        try {
            result = dispatchByEventCode(item);
        } catch (Exception error) {
            result = buildFailureResult(dispatchEventCode, error);
        }

        JsonObject submitBody = new JsonObject();
        submitBody.addProperty("taskId", taskId);
        submitBody.addProperty("messageId", messageId);
        submitBody.addProperty("success", boolMember(result, "success"));
        submitBody.addProperty("detail", stringMember(result, "detail"));
        JsonElement errorCodeElement = result.get("errorCode");
        if (errorCodeElement == null || errorCodeElement.isJsonNull()) {
            submitBody.add("errorCode", null);
        } else {
            submitBody.addProperty("errorCode", errorCodeElement.getAsString());
        }
        submitBody.add("output", objectMember(result, "output"));
        JsonObject response = post("/worker-api/v1/workers/" + encoded(workerId) + ":submit-result", submitBody);
        log("submitted result: " + response.get("data"));
    }

    private JsonObject dispatchByEventCode(JsonObject item) throws Exception {
        String dispatchEventCode = stringMember(item, "eventCode");
        if ("crawler.fetch-page".equals(dispatchEventCode)) {
            return handleCrawlerFetchPage(item);
        }
        throw new IllegalArgumentException("Unsupported eventCode: " + dispatchEventCode);
    }

    private JsonObject handleCrawlerFetchPage(JsonObject item) throws Exception {
        String url = lookupDispatchUrl(item);
        if (url == null || url.isBlank()) {
            return buildValidationFailure(item, "url is required in TaskDispatchItem.input.url");
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return buildFetchFailure(item, url, startedAt, error);
        }

        JsonObject output = baseOutput(item);
        output.addProperty("url", response.uri().toString());
        output.addProperty("statusCode", response.statusCode());
        output.addProperty("title", extractHtmlTitle(response.body()));
        output.addProperty("fetchedAt", Instant.now().toString());
        output.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);

        JsonObject result = new JsonObject();
        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        result.addProperty("success", success);
        result.addProperty("detail", success ? "crawler-success" : "crawler-http-" + response.statusCode());
        if (success) {
            result.add("errorCode", null);
        } else {
            result.addProperty("errorCode", "HTTP_" + response.statusCode());
        }
        result.add("output", output);
        return result;
    }

    private JsonObject buildValidationFailure(JsonObject item, String detail) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("detail", detail);
        result.addProperty("errorCode", "INVALID_INPUT");
        result.add("output", baseOutput(item));
        return result;
    }

    private JsonObject buildFetchFailure(JsonObject item, String url, long startedAt, Exception error) {
        JsonObject output = baseOutput(item);
        output.addProperty("url", url);
        output.addProperty("fetchedAt", Instant.now().toString());
        output.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);

        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("detail", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        result.addProperty("errorCode", "FETCH_ERROR");
        result.add("output", output);
        return result;
    }

    private JsonObject buildFailureResult(String dispatchEventCode, Exception error) {
        JsonObject output = new JsonObject();
        output.addProperty("workerId", workerId);
        output.addProperty("eventCode", dispatchEventCode);
        output.add("workerProfile", workerProfile());

        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("detail", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        result.addProperty("errorCode", "WORKER_HANDLER_ERROR");
        result.add("output", output);
        return result;
    }

    private JsonObject baseOutput(JsonObject item) {
        JsonObject output = new JsonObject();
        output.addProperty("workerId", workerId);
        output.addProperty("eventCode", stringMember(item, "eventCode"));
        output.addProperty("integrationProbe", "cross-language-java-polling");
        output.add("workerProfile", workerProfile());
        return output;
    }

    private JsonObject workerProfile() {
        JsonObject workerProfile = new JsonObject();
        workerProfile.addProperty("runtime", "java-polling-worker");
        workerProfile.addProperty("language", "java");
        workerProfile.addProperty("workerId", workerId);
        return workerProfile;
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Mass-Api-Key", workerKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String responseBody = response.body() != null ? response.body() : "";
        JsonObject envelope = JsonParser.parseString(responseBody.isBlank() ? "{}" : responseBody).getAsJsonObject();
        int code = envelope.has("code") && !envelope.get("code").isJsonNull()
                ? envelope.get("code").getAsInt()
                : Integer.MIN_VALUE;
        if (response.statusCode() / 100 != 2 || code != 0) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + path + ": "
                    + (envelope.has("msg") ? envelope.get("msg").getAsString() : "unknown error"));
        }
        return envelope;
    }

    private void safeShutdown(String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        log("shutting down: " + reason);
        try {
            post("/worker-api/v1/workers/" + encoded(workerId) + ":offline", jsonObject("reason", reason));
        } catch (Exception error) {
            log("offline failed: " + error.getMessage());
        }
    }

    private String lookupDispatchUrl(JsonObject item) {
        JsonObject input = optionalObjectMember(item, "input");
        if (input != null) {
            String candidate = optionalStringMember(input, "url");
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        JsonObject sharedConfig = optionalObjectMember(item, "sharedConfig");
        if (sharedConfig != null) {
            return optionalStringMember(sharedConfig, "url");
        }
        return null;
    }

    private static JsonObject jsonObject(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static JsonObject objectMember(JsonObject object, String fieldName) {
        JsonObject nested = optionalObjectMember(object, fieldName);
        return nested != null ? nested : new JsonObject();
    }

    private static JsonObject optionalObjectMember(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return null;
        }
        JsonElement value = object.get(fieldName);
        return value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray arrayMember(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return new JsonArray();
        }
        JsonElement value = object.get(fieldName);
        return value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String stringMember(JsonObject object, String fieldName) {
        String value = optionalStringMember(object, fieldName);
        return value != null ? value : "";
    }

    private static String optionalStringMember(JsonObject object, String fieldName) {
        if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return null;
        }
        return object.get(fieldName).getAsString();
    }

    private static boolean boolMember(JsonObject object, String fieldName) {
        return object != null && object.has(fieldName) && !object.get(fieldName).isJsonNull()
                && object.get(fieldName).getAsBoolean();
    }

    private static String extractHtmlTitle(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = TITLE_PATTERN.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnv(String name, String fallback) {
        String value = env(name, fallback);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long longEnv(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed = Long.parseLong(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return parsed;
    }

    private static boolean booleanEnv(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private static String[] splitCsv(String value) {
        return value == null || value.isBlank()
                ? new String[0]
                : value.trim().split("\\s*,\\s*");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value, "value"), StandardCharsets.UTF_8);
    }

    private static String normalizeBaseUrl(String value) {
        Objects.requireNonNull(value, "value");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void log(String message) {
        System.out.println("[java-worker:" + workerId + "] " + message);
    }
}
