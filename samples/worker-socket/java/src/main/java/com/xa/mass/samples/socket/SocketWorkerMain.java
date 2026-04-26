package com.xa.mass.samples.socket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SocketWorkerMain {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String workerId;
    private final String socketHost;
    private final int socketPort;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile Thread readerThread;

    private SocketWorkerMain() {
        this.workerId = requiredEnv("WORKER_ID");
        this.socketHost = env("SOCKET_HOST", "127.0.0.1");
        this.socketPort = intEnv("SOCKET_PORT");
    }

    public static void main(String[] args) throws Exception {
        new SocketWorkerMain().run();
    }

    private void run() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "java-socket-worker-shutdown"));

        Socket created = new Socket();
        created.connect(new InetSocketAddress(socketHost, socketPort), (int) TimeUnit.SECONDS.toMillis(10));
        created.setTcpNoDelay(true);
        BufferedWriter createdWriter = new BufferedWriter(
                new OutputStreamWriter(created.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader createdReader = new BufferedReader(
                new InputStreamReader(created.getInputStream(), StandardCharsets.UTF_8));

        this.socket = created;
        this.writer = createdWriter;
        log("connected to tcp://" + socketHost + ":" + socketPort);
        sendFrame(buildHelloFrame());
        startReaderLoop(createdReader);

        shutdownLatch.await();
    }

    private void startReaderLoop(BufferedReader reader) {
        Thread thread = new Thread(() -> {
            try {
                String line;
                while (!shuttingDown.get() && (line = reader.readLine()) != null) {
                    handleFrame(line);
                }
            } catch (Exception error) {
                if (!shuttingDown.get()) {
                    System.err.println("[java-socket-worker:" + workerId + "] socket reader failed: " + error.getMessage());
                    error.printStackTrace(System.err);
                }
            } finally {
                shutdown();
            }
        }, "java-socket-worker-reader-" + workerId);
        thread.setDaemon(true);
        thread.start();
        this.readerThread = thread;
    }

    private void handleFrame(String rawFrame) throws Exception {
        JsonObject frame = JsonParser.parseString(rawFrame).getAsJsonObject();
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
            result = buildFailureResult("Unsupported eventCode: "
                    + (eventCode == null || eventCode.isBlank() ? "<missing>" : eventCode),
                    "UNSUPPORTED_EVENT_CODE");
        }
        sendResult(frame, result);
    }

    private JsonObject handleDemoDispatch(JsonObject frame) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("detail", "completed by external java socket worker");
        JsonObject output = new JsonObject();
        output.add("taskInput", objectMember(frame, "input"));
        result.add("output", output);
        return result;
    }

    private JsonObject handleCrawlerFetchPage(JsonObject frame) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("detail", "crawler fetch simulated by external java socket worker");
        JsonObject output = new JsonObject();
        output.addProperty("url", lookupDispatchUrl(frame));
        output.addProperty("fetchedAt", Instant.now().toString());
        result.add("output", output);
        return result;
    }

    private JsonObject buildFailureResult(String detail, String errorCode) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("detail", detail);
        result.addProperty("errorCode", errorCode);
        result.add("output", new JsonObject());
        return result;
    }

    private void sendResult(JsonObject taskFrame, JsonObject result) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("messageId", stringMember(taskFrame, "messageId"));
        payload.addProperty("taskId", stringMember(taskFrame, "taskId"));
        payload.addProperty("success", boolMember(result, "success"));
        payload.addProperty("detail", stringMember(result, "detail"));
        payload.add("errorCode", result.has("errorCode") ? result.get("errorCode") : null);

        JsonObject output = new JsonObject();
        output.addProperty("status", boolMember(result, "success") ? "SUCCESS" : "FAILED");
        output.addProperty("message", stringMember(result, "detail"));
        output.addProperty("integrationProbe", "cross-language-java-socket");
        output.add("workerProfile", workerProfile());

        JsonObject execution = new JsonObject();
        execution.addProperty("transport", "socket");
        execution.addProperty("dispatchShape", "canonical-task-dispatch");
        execution.addProperty("resultShape", "canonical-task-result");
        execution.addProperty("respondedAt", Instant.now().toString());
        output.add("execution", execution);

        output.addProperty("eventCode", stringMember(taskFrame, "eventCode"));
        mergeInto(output, objectMember(result, "output"));
        payload.add("output", output);
        sendFrame(GSON.toJson(payload));
    }

    private JsonObject workerProfile() {
        JsonObject profile = new JsonObject();
        profile.addProperty("runtime", "java-socket-worker");
        profile.addProperty("language", "java");
        profile.addProperty("workerId", workerId);
        return profile;
    }

    private String lookupDispatchUrl(JsonObject frame) {
        JsonObject input = optionalObjectMember(frame, "input");
        if (input != null) {
            String value = optionalStringMember(input, "url");
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        JsonObject sharedConfig = optionalObjectMember(frame, "sharedConfig");
        if (sharedConfig != null) {
            return optionalStringMember(sharedConfig, "url");
        }
        return null;
    }

    private boolean isCanonicalTaskDispatch(JsonObject frame) {
        return !stringMember(frame, "taskId").isBlank()
                && !stringMember(frame, "messageId").isBlank()
                && !frame.has("success");
    }

    private String buildHelloFrame() {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("workerId", workerId);
        return GSON.toJson(hello);
    }

    private synchronized void sendFrame(String frameJson) throws Exception {
        BufferedWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new IllegalStateException("socket writer is not available");
        }
        currentWriter.write(frameJson);
        currentWriter.newLine();
        currentWriter.flush();
    }

    private void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            shutdownLatch.countDown();
            return;
        }
        closeQuietly(writer);
        closeQuietly(socket);
        Thread currentReader = readerThread;
        if (currentReader != null && currentReader != Thread.currentThread()) {
            currentReader.interrupt();
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static int intEnv(String name) {
        String value = requiredEnv(name);
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
        return parsed;
    }

    private void log(String message) {
        System.out.println("[java-socket-worker:" + workerId + "] " + message);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Ignore close failures on shutdown.
        }
    }
}
