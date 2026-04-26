package com.xa.mass.mock.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Launches an external Node.js worker process for cross-language black-box E2E tests.
 *
 * <p>The worker process speaks only public repo entry scripts and has no direct
 * access to in-JVM helper classes or runtime objects.
 */
public final class ExternalNodeWorkerProcess implements AutoCloseable {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Process process;
    private final ThrowingCloseAction closeAction;
    private final Thread outputPump;
    private final StringBuilder capturedOutput = new StringBuilder();

    private ExternalNodeWorkerProcess(Process process, ThrowingCloseAction closeAction) {
        this.process = Objects.requireNonNull(process, "process");
        this.closeAction = closeAction;
        this.outputPump = new Thread(this::pumpOutput, "external-node-worker-output");
        this.outputPump.setDaemon(true);
        this.outputPump.start();
    }

    public static ExternalNodeWorkerProcess startWebSocketSample(String workerId, URI wsUri) throws Exception {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(wsUri, "wsUri");

        return startRepoScript("samples/worker-websocket/node/worker.mjs", Map.of(
                "WORKER_ID", workerId,
                "WS_URL", wsUri.toString()
        ));
    }

    public static ExternalNodeWorkerProcess startSocket(String workerId, String host, int port) throws Exception {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(host, "host");

        return startClasspathScript("node/node_socket_worker.mjs", Map.of(
                "WORKER_ID", workerId,
                "SOCKET_HOST", host,
                "SOCKET_PORT", String.valueOf(port)
        ));
    }

    public static ExternalNodeWorkerProcess startPollingSample(String baseUrl,
                                                               String workerId,
                                                               String workerKey) throws Exception {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(workerKey, "workerKey");

        return startRepoScript("samples/worker-polling/node/worker.mjs", Map.of(
                "MASS_BASE_URL", baseUrl,
                "MASS_WORKER_ID", workerId,
                "MASS_WORKER_KEY", workerKey,
                "MASS_POLL_INTERVAL_MS", "200",
                "MASS_HEARTBEAT_INTERVAL_MS", "1000"
        ), () -> postWorkerOffline(baseUrl, workerId, workerKey));
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

    private static ExternalNodeWorkerProcess startClasspathScript(String classpathLocation,
                                                                  Map<String, String> environment) throws Exception {
        return startProcess(resolveScriptPath(classpathLocation), environment, null);
    }

    private static ExternalNodeWorkerProcess startRepoScript(String repoRelativePath,
                                                             Map<String, String> environment) throws Exception {
        return startProcess(resolveRepoFile(repoRelativePath), environment, null);
    }

    private static ExternalNodeWorkerProcess startRepoScript(String repoRelativePath,
                                                             Map<String, String> environment,
                                                             ThrowingCloseAction closeAction) throws Exception {
        return startProcess(resolveRepoFile(repoRelativePath), environment, closeAction);
    }

    private static ExternalNodeWorkerProcess startProcess(Path scriptPath,
                                                          Map<String, String> environment,
                                                          ThrowingCloseAction closeAction) throws Exception {
        String nodeBin = resolveNodeBinary();
        ProcessBuilder processBuilder = new ProcessBuilder(nodeBin, scriptPath.toString());
        processBuilder.redirectErrorStream(true);
        if (environment != null && !environment.isEmpty()) {
            processBuilder.environment().putAll(new LinkedHashMap<>(environment));
        }
        return new ExternalNodeWorkerProcess(processBuilder.start(), closeAction);
    }

    private static String resolveNodeBinary() {
        String property = System.getProperty("mass.test.nodeBin");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("NODE_BIN");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "node";
    }

    private static Path resolveRepoFile(String repoRelativePath) {
        Path current = Paths.get("").toAbsolutePath();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(repoRelativePath);
            if (Files.exists(cursor.resolve("pom.xml")) && Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Repo script not found: " + repoRelativePath
                + " from cwd=" + current);
    }

    private static Path resolveScriptPath(String classpathLocation) throws Exception {
        URL resource = ExternalNodeWorkerProcess.class.getClassLoader().getResource(classpathLocation);
        if (resource == null) {
            throw new IllegalStateException("Node worker script not found on classpath: " + classpathLocation);
        }
        return Paths.get(resource.toURI());
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
                        + "/worker-api/workers/" + workerId + "/offline"))
                .header("Content-Type", "application/json")
                .header("X-Mass-Api-Key", workerKey)
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"external-node-process-close\"}"))
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
