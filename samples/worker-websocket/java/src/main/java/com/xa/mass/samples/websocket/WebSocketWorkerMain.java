package com.xa.mass.samples.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketWorkerMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String workerId;
    private final String wsUrl;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile WebSocket webSocket;

    private WebSocketWorkerMain() {
        this.workerId = requiredEnv("WORKER_ID");
        this.wsUrl = requiredEnv("WS_URL");
    }

    public static void main(String[] args) throws Exception {
        new WebSocketWorkerMain().run();
    }

    private void run() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(1000, "shutdown-hook"),
                "java-websocket-worker-shutdown"));

        URI connectUri = URI.create(wsUrl + (wsUrl.contains("?") ? "&" : "?")
                + "workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8));
        log("connecting to " + connectUri);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket connected = client.newWebSocketBuilder()
                .buildAsync(connectUri, new WorkerListener())
                .join();
        this.webSocket = connected;

        shutdownLatch.await();
    }

    private void handleFrame(String rawFrame) {
        JsonObject frame = JsonParser.parseString(rawFrame).getAsJsonObject();
        if (isControlCompatibilityFrame(frame)) {
            log("ignoring control compatibility frame eventCode=" + stringMember(frame, "eventCode"));
            return;
        }
        if (!isCanonicalTaskDispatch(frame)) {
            log("ignoring unsupported frame");
            return;
        }

        String eventCode = stringMember(frame, "eventCode");
        log("received task frame taskId=" + stringMember(frame, "taskId")
                + " messageId=" + stringMember(frame, "messageId")
                + " eventCode=" + eventCode);

        JsonObject result;
        if ("demo.dispatch".equals(eventCode)) {
            result = handleDemoDispatch(frame);
        } else if ("crawler.fetch-page".equals(eventCode)) {
            result = handleCrawlerFetchPage(frame);
        } else {
            result = buildFailureResult(frame,
                    "Unsupported eventCode: " + (eventCode == null || eventCode.isBlank() ? "<missing>" : eventCode),
                    "UNSUPPORTED_EVENT_CODE");
        }
        sendResult(frame, result);
    }

    private JsonObject handleDemoDispatch(JsonObject frame) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("detail", "completed by external java worker");
        JsonObject output = new JsonObject();
        output.add("taskInput", objectMember(frame, "input"));
        result.add("output", output);
        return result;
    }

    private JsonObject handleCrawlerFetchPage(JsonObject frame) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("detail", "crawler fetch simulated by external java websocket worker");
        JsonObject output = new JsonObject();
        output.addProperty("url", lookupDispatchUrl(frame));
        output.addProperty("fetchedAt", Instant.now().toString());
        result.add("output", output);
        return result;
    }

    private void sendResult(JsonObject taskFrame, JsonObject result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("messageId", stringMember(taskFrame, "messageId"));
        payload.addProperty("taskId", stringMember(taskFrame, "taskId"));
        payload.addProperty("success", boolMember(result, "success"));
        payload.addProperty("detail", stringMember(result, "detail"));

        JsonElement errorCode = result.get("errorCode");
        payload.add("errorCode", errorCode == null ? JsonNull.INSTANCE : errorCode);

        JsonObject output = new JsonObject();
        output.addProperty("status", boolMember(result, "success") ? "SUCCESS" : "FAILED");
        output.addProperty("message", stringMember(result, "detail"));
        output.addProperty("integrationProbe", "cross-language-java-websocket");
        output.add("workerProfile", workerProfile());

        JsonObject execution = new JsonObject();
        execution.addProperty("transport", "websocket");
        execution.addProperty("dispatchShape", "canonical-task-dispatch");
        execution.addProperty("resultShape", "canonical-task-result");
        execution.addProperty("respondedAt", Instant.now().toString());
        output.add("execution", execution);

        output.addProperty("eventCode", stringMember(taskFrame, "eventCode"));
        mergeInto(output, objectMember(result, "output"));
        payload.add("output", output);

        String json = GSON.toJson(payload);
        webSocket.sendText(json, true).join();
    }

    private JsonObject buildFailureResult(JsonObject frame, String detail, String errorCode) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("detail", detail);
        result.addProperty("errorCode", errorCode);
        result.add("output", new JsonObject());
        return result;
    }

    private String lookupDispatchUrl(JsonObject frame) {
        JsonObject input = optionalObjectMember(frame, "input");
        if (input != null) {
            String url = optionalStringMember(input, "url");
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        JsonObject sharedConfig = optionalObjectMember(frame, "sharedConfig");
        if (sharedConfig != null) {
            return optionalStringMember(sharedConfig, "url");
        }
        return null;
    }

    private JsonObject workerProfile() {
        JsonObject profile = new JsonObject();
        profile.addProperty("runtime", "java-websocket-worker");
        profile.addProperty("language", "java");
        profile.addProperty("workerId", workerId);
        return profile;
    }

    private boolean isControlCompatibilityFrame(JsonObject frame) {
        return !stringMember(frame, "eventCode").isBlank() && stringMember(frame, "taskId").isBlank();
    }

    private boolean isCanonicalTaskDispatch(JsonObject frame) {
        return !stringMember(frame, "taskId").isBlank()
                && !stringMember(frame, "messageId").isBlank()
                && !frame.has("success");
    }

    private void shutdown(int statusCode, String reason) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        WebSocket socket = this.webSocket;
        if (socket != null) {
            try {
                socket.sendClose(statusCode, reason).get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                socket.abort();
            }
        }
        shutdownLatch.countDown();
    }

    private static void mergeInto(JsonObject target, JsonObject source) {
        for (String key : source.keySet()) {
            target.add(key, source.get(key));
        }
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

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private void log(String message) {
        System.out.println("[java-websocket-worker:" + workerId + "] " + message);
    }

    private final class WorkerListener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log("connected to " + webSocket.getSubprotocol());
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String frame = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleFrame(frame);
                } catch (Exception error) {
                    System.err.println("[java-websocket-worker:" + workerId + "] failed to handle frame: " + error.getMessage());
                    error.printStackTrace(System.err);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("socket closed code=" + statusCode + " reason=" + reason);
            shutdownLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[java-websocket-worker:" + workerId + "] websocket error: " + error.getMessage());
            error.printStackTrace(System.err);
            shutdownLatch.countDown();
        }
    }
}
