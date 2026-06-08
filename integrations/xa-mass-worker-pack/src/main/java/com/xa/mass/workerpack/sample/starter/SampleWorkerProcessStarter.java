package com.xa.mass.workerpack.sample.starter;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Launches the external sample-worker supervisor script for memory-local runs.
 *
 * <p>The server shell stays responsible only for API-key credentials and HTTP
 * availability. The sample launcher script owns worker registration plus
 * child-process startup under {@code integrations/samples}.
 */
@Component
@Profile("memory-local")
@ConditionalOnProperty(prefix = "sample.worker", name = "auto-start", havingValue = "true")
public class SampleWorkerProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(SampleWorkerProcessStarter.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Environment environment;

    @Value("${sample.worker.node-bin:node}")
    private String nodeBin;

    @Value("${sample.worker.launcher-script:integrations/samples/dev/scenario/launch-workers.mjs}")
    private String launcherScript;

    private volatile Process launcherProcess;
    private volatile Thread outputPump;

    public SampleWorkerProcessStarter(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onApplicationReady() {
        if (launcherProcess != null && launcherProcess.isAlive()) {
            log.info("Sample worker launcher already running");
            return;
        }

        Path scriptPath = resolveRepoFile(launcherScript);
        Path repoRoot = resolveRepoRoot(scriptPath);
        String baseUrl = "http://127.0.0.1:" + resolveHttpPort();
        String wsUrl = "ws://127.0.0.1:" + resolveWebSocketPort() + "/ws";

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(nodeBin, scriptPath.toString());
            processBuilder.directory(repoRoot.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().put("MASS_BASE_URL", baseUrl);
            processBuilder.environment().put("MASS_WS_URL", wsUrl);
            processBuilder.environment().putIfAbsent("NODE_BIN", nodeBin);
            launcherProcess = processBuilder.start();
            outputPump = new Thread(this::pumpOutput, "sample-worker-launcher-output");
            outputPump.setDaemon(true);
            outputPump.start();
            log.info("Started sample worker launcher script={} baseUrl={} wsUrl={}",
                    scriptPath,
                    baseUrl,
                    wsUrl);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start sample worker launcher: " + scriptPath, e);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (launcherProcess == null) {
            return;
        }
        try {
            if (launcherProcess.isAlive()) {
                launcherProcess.destroy();
                if (!launcherProcess.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    launcherProcess.destroyForcibly();
                    launcherProcess.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (outputPump != null) {
                try {
                    outputPump.join(SHUTDOWN_TIMEOUT.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            launcherProcess = null;
            outputPump = null;
        }
    }

    private void pumpOutput() {
        Process process = launcherProcess;
        if (process == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[sample-worker-launcher] {}", line);
            }
        } catch (IOException e) {
            log.warn("Sample worker launcher output pump stopped: {}", e.getMessage());
        }
    }

    private int resolveHttpPort() {
        return Integer.parseInt(environment.getProperty(
                "local.server.port",
                environment.getProperty("server.port", "8088")
        ));
    }

    private int resolveWebSocketPort() {
        return Integer.parseInt(environment.getProperty("mass.websocket.port", "18088"));
    }

    private Path resolveRepoFile(String repoRelativePath) {
        Path current = Paths.get("").toAbsolutePath();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(repoRelativePath);
            if (Files.exists(cursor.resolve("pom.xml")) && Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Repo script not found: " + repoRelativePath + " from cwd=" + current);
    }

    private Path resolveRepoRoot(Path scriptPath) {
        Path current = scriptPath.toAbsolutePath();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("pom.xml"))) {
                return cursor;
            }
        }
        throw new IllegalStateException("Repo root not found for script: " + scriptPath);
    }
}
