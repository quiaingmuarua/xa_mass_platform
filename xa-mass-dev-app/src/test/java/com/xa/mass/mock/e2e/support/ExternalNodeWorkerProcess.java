package com.xa.mass.mock.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Launches an external Node.js worker process for cross-language black-box E2E tests.
 *
 * <p>The worker process speaks only the public WebSocket transport protocol and has
 * no direct access to in-JVM helper classes or runtime objects.
 */
public final class ExternalNodeWorkerProcess implements AutoCloseable {

    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Process process;
    private final Thread outputPump;
    private final StringBuilder capturedOutput = new StringBuilder();

    private ExternalNodeWorkerProcess(Process process) {
        this.process = Objects.requireNonNull(process, "process");
        this.outputPump = new Thread(this::pumpOutput, "external-node-worker-output");
        this.outputPump.setDaemon(true);
        this.outputPump.start();
    }

    public static ExternalNodeWorkerProcess start(String workerId, URI wsUri) throws Exception {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(wsUri, "wsUri");

        String nodeBin = resolveNodeBinary();
        Path scriptPath = resolveScriptPath("node/node_ws_worker.mjs");

        ProcessBuilder processBuilder = new ProcessBuilder(nodeBin, scriptPath.toString());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("WORKER_ID", workerId);
        processBuilder.environment().put("WS_URL", wsUri.toString());

        return new ExternalNodeWorkerProcess(processBuilder.start());
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
            joinOutputPump();
            return;
        }

        process.destroy();
        if (!process.waitFor(DEFAULT_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(DEFAULT_SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
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

    private static Path resolveScriptPath(String classpathLocation) throws Exception {
        URL resource = ExternalNodeWorkerProcess.class.getClassLoader().getResource(classpathLocation);
        if (resource == null) {
            throw new IllegalStateException("Node worker script not found on classpath: " + classpathLocation);
        }
        return Paths.get(resource.toURI());
    }
}
