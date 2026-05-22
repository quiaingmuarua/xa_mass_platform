package com.xa.mass.server.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Launches external Java worker samples for cross-language black-box tests.
 */
public final class ExternalJavaWorkerProcess implements AutoCloseable {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static volatile boolean pollingSampleBuilt;
    private static volatile boolean websocketSampleBuilt;
    private static volatile boolean socketSampleBuilt;

    private final Process process;
    private final ThrowingCloseAction closeAction;
    private final Thread outputPump;
    private final StringBuilder capturedOutput = new StringBuilder();

    private ExternalJavaWorkerProcess(Process process, ThrowingCloseAction closeAction) {
        this.process = Objects.requireNonNull(process, "process");
        this.closeAction = closeAction;
        this.outputPump = new Thread(this::pumpOutput, "external-java-worker-output");
        this.outputPump.setDaemon(true);
        this.outputPump.start();
    }

    public static ExternalJavaWorkerProcess startPollingSample(String baseUrl,
                                                               String workerId,
                                                               String workerKey) throws Exception {
        return startPollingSample(baseUrl, workerId, workerKey, null);
    }

    public static ExternalJavaWorkerProcess startPollingSample(String baseUrl,
                                                               String workerId,
                                                               String workerKey,
                                                               String workerGroupId) throws Exception {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(workerKey, "workerKey");

        ensurePollingSampleBuilt();
        Map<String, String> environment = new LinkedHashMap<>(Map.of(
                "MASS_BASE_URL", baseUrl,
                "MASS_WORKER_ID", workerId,
                "MASS_WORKER_KEY", workerKey,
                "MASS_POLL_INTERVAL_MS", "200",
                "MASS_HEARTBEAT_INTERVAL_MS", "1000"
        ));
        if (workerGroupId != null && !workerGroupId.isBlank()) {
            environment.put("MASS_WORKER_GROUP_ID", workerGroupId);
        }
        return startJar(resolveRepoFile("samples/worker-polling/java/target/worker-polling-java-sample.jar"),
                environment, () -> postWorkerOffline(baseUrl, workerId, workerKey));
    }

    public static ExternalJavaWorkerProcess startWebSocketSample(String workerId, URI wsUri) throws Exception {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(wsUri, "wsUri");

        ensureWebSocketSampleBuilt();
        return startJar(resolveRepoFile("samples/worker-websocket/java/target/worker-websocket-java-sample.jar"), Map.of(
                "WORKER_ID", workerId,
                "WS_URL", wsUri.toString()
        ), null);
    }

    public static ExternalJavaWorkerProcess startSocketSample(String workerId, String host, int port) throws Exception {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(host, "host");
        if (port <= 0) {
            throw new IllegalArgumentException("port must be positive");
        }

        ensureSocketSampleBuilt();
        return startJar(resolveRepoFile("samples/worker-socket/java/target/worker-socket-java-sample.jar"), Map.of(
                "WORKER_ID", workerId,
                "SOCKET_HOST", host,
                "SOCKET_PORT", String.valueOf(port)
        ), null);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void assertAlive(String contextMessage) {
        if (isAlive()) {
            return;
        }
        throw new AssertionError(contextMessage + ". Exit code=" + process.exitValue()
                + "\nCaptured output:\n" + capturedOutput());
    }

    public String capturedOutput() {
        synchronized (capturedOutput) {
            return capturedOutput.toString();
        }
    }

    @Override
    public void close() throws Exception {
        if (!process.isAlive()) {
            runCloseAction();
            joinOutputPump();
            return;
        }

        process.destroy();
        if (!process.waitFor(DEFAULT_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(DEFAULT_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        runCloseAction();
        joinOutputPump();
    }

    private void pumpOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (capturedOutput) {
                    capturedOutput.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException ignored) {
            synchronized (capturedOutput) {
                capturedOutput.append("[output pump stopped: ").append(ignored.getMessage()).append(']')
                        .append(System.lineSeparator());
            }
        }
    }

    private void joinOutputPump() throws InterruptedException {
        outputPump.join(DEFAULT_SHUTDOWN_TIMEOUT.toMillis());
    }

    private static void ensurePollingSampleBuilt() throws Exception {
        if (pollingSampleBuilt) {
            return;
        }
        synchronized (ExternalJavaWorkerProcess.class) {
            if (pollingSampleBuilt) {
                return;
            }
            buildSample("samples/worker-polling/java/pom.xml", "Java polling");
            pollingSampleBuilt = true;
        }
    }

    private static void ensureWebSocketSampleBuilt() throws Exception {
        if (websocketSampleBuilt) {
            return;
        }
        synchronized (ExternalJavaWorkerProcess.class) {
            if (websocketSampleBuilt) {
                return;
            }
            buildSample("samples/worker-websocket/java/pom.xml", "Java websocket");
            websocketSampleBuilt = true;
        }
    }

    private static void ensureSocketSampleBuilt() throws Exception {
        if (socketSampleBuilt) {
            return;
        }
        synchronized (ExternalJavaWorkerProcess.class) {
            if (socketSampleBuilt) {
                return;
            }
            buildSample("samples/worker-socket/java/pom.xml", "Java socket");
            socketSampleBuilt = true;
        }
    }

    private static void buildSample(String pomPath, String label) throws Exception {
        Path repoRoot = resolveRepoRoot();
        String wrapper = resolveMavenCommand(repoRoot);
        ProcessBuilder processBuilder = new ProcessBuilder(
                wrapper,
                "-q",
                "-f",
                pomPath,
                "-DskipTests",
                "package");
        processBuilder.directory(repoRoot.toFile());
        processBuilder.redirectErrorStream(true);
        Process buildProcess = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputPump = new Thread(() -> pumpOutput(buildProcess, output),
                "external-java-worker-build-output");
        outputPump.setDaemon(true);
        outputPump.start();
        if (!buildProcess.waitFor(DEFAULT_SHUTDOWN_TIMEOUT.toMillis() * 6, TimeUnit.MILLISECONDS)) {
            buildProcess.destroyForcibly();
            throw new IllegalStateException("Timed out while building " + label + " sample");
        }
        outputPump.join(DEFAULT_SHUTDOWN_TIMEOUT.toMillis());
        if (buildProcess.exitValue() != 0) {
            throw new IllegalStateException("Failed to build " + label + " sample. Output:\n" + output);
        }
    }

    private static String resolveMavenCommand(Path repoRoot) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (windows) {
            if (Files.exists(repoRoot.resolve("mvnw.cmd"))) {
                return repoRoot.resolve("mvnw.cmd").toString();
            }
            if (Files.exists(repoRoot.resolve("mvnw"))) {
                return repoRoot.resolve("mvnw").toString();
            }
            return "mvn.cmd";
        }
        if (Files.exists(repoRoot.resolve("mvnw"))) {
            return repoRoot.resolve("mvnw").toString();
        }
        return "mvn";
    }

    private static ExternalJavaWorkerProcess startJar(Path jarPath,
                                                      Map<String, String> environment,
                                                      ThrowingCloseAction closeAction) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(resolveJavaBinary(), "-jar", jarPath.toString());
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(resolveRepoRoot().toFile());
        if (environment != null && !environment.isEmpty()) {
            processBuilder.environment().putAll(new LinkedHashMap<>(environment));
        }
        return new ExternalJavaWorkerProcess(processBuilder.start(), closeAction);
    }

    private static String resolveJavaBinary() {
        String property = System.getProperty("mass.test.javaBin");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("JAVA_BIN");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "java";
    }

    private static Path resolveRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("samples"))
                    && Files.exists(cursor.resolve("samples/worker-polling/java/pom.xml"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("Repo root not found from cwd=" + current);
    }

    private static Path resolveRepoFile(String repoRelativePath) {
        Path repoRoot = resolveRepoRoot();
        Path candidate = repoRoot.resolve(repoRelativePath);
        if (!Files.exists(candidate)) {
            throw new IllegalStateException("Repo file not found: " + repoRelativePath);
        }
        return candidate;
    }

    private static void pumpOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (IOException ignored) {
            output.append("[build output pump stopped: ").append(ignored.getMessage()).append(']')
                    .append(System.lineSeparator());
        }
    }

    private void runCloseAction() throws Exception {
        if (closeAction == null) {
            return;
        }
        closeAction.run();
    }

    private static void postWorkerOffline(String baseUrl,
                                          String workerId,
                                          String workerKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(baseUrl)
                        + "/worker-api/v1/workers/" + workerId + ":offline"))
                .header("Content-Type", "application/json")
                .header("X-Mass-Api-Key", workerKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"external-java-process-close\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Failed to mark worker offline, status=" + response.statusCode()
                    + ", body=" + response.body());
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @FunctionalInterface
    private interface ThrowingCloseAction {
        void run() throws Exception;
    }
}
