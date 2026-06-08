package com.xa.mass.server.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Launches the Java scenario launcher as an external black-box process.
 */
public final class ExternalJavaScenarioLauncherProcess implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final String TASK_LAUNCHER_JAR =
            "integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar";
    private static final String WORKER_LAUNCHER_JAR =
            "integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar";
    private static volatile boolean scenarioLauncherBuilt;

    private final Process process;
    private final Thread outputPump;
    private final StringBuilder capturedOutput = new StringBuilder();

    private ExternalJavaScenarioLauncherProcess(Process process) {
        this.process = Objects.requireNonNull(process, "process");
        this.outputPump = new Thread(this::pumpOutput, "external-java-scenario-launcher-output");
        this.outputPump.setDaemon(true);
        this.outputPump.start();
    }

    public static String runTaskLauncher(String baseUrl,
                                         Path scenarioDir,
                                         String taskApiKey,
                                         Duration timeout) throws Exception {
        try (ExternalJavaScenarioLauncherProcess process = startProcess(
                TASK_LAUNCHER_JAR,
                baseUrl,
                null,
                scenarioDir,
                taskApiKey)) {
            return process.awaitExit(timeout, "Java scenario task launcher");
        }
    }

    public static ExternalJavaScenarioLauncherProcess startWorkerLauncher(String baseUrl,
                                                                          String webSocketUrl,
                                                                          Path scenarioDir,
                                                                          String taskApiKey) throws Exception {
        return startProcess(WORKER_LAUNCHER_JAR, baseUrl, webSocketUrl, scenarioDir, taskApiKey);
    }

    private static ExternalJavaScenarioLauncherProcess startProcess(String jarPath,
                                                                    String baseUrl,
                                                                    String webSocketUrl,
                                                                    Path scenarioDir,
                                                                    String taskApiKey) throws Exception {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(jarPath, "jarPath");
        Objects.requireNonNull(scenarioDir, "scenarioDir");
        Objects.requireNonNull(taskApiKey, "taskApiKey");

        ensureScenarioLauncherBuilt();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(resolveJavaBinary());
        command.add("-jar");
        command.add(resolveRepoFile(jarPath).toString());
        command.add("--base-url");
        command.add(baseUrl);
        if (webSocketUrl != null && !webSocketUrl.isBlank()) {
            command.add("--websocket-url");
            command.add(webSocketUrl);
        }
        command.add("--scenario-dir");
        command.add(scenarioDir.toString());
        command.add("--task-api-key");
        command.add(taskApiKey);
        command.add("--max-polling-workers");
        command.add("1");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.directory(resolveRepoRoot().toFile());
        return new ExternalJavaScenarioLauncherProcess(processBuilder.start());
    }

    public String awaitExit(Duration timeout, String contextMessage) throws Exception {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError(contextMessage + " did not exit within " + timeout
                    + "\nCaptured output:\n" + capturedOutput());
        }
        joinOutputPump();
        if (process.exitValue() != 0) {
            throw new AssertionError(contextMessage + " failed with exit code=" + process.exitValue()
                    + "\nCaptured output:\n" + capturedOutput());
        }
        return capturedOutput();
    }

    public String capturedOutput() {
        synchronized (capturedOutput) {
            return capturedOutput.toString();
        }
    }

    public void assertAlive(String message) {
        if (!process.isAlive()) {
            throw new AssertionError(message + "\nCaptured output:\n" + capturedOutput());
        }
    }

    @Override
    public void close() throws Exception {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
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
        outputPump.join(DEFAULT_TIMEOUT.toMillis());
    }

    private static void ensureScenarioLauncherBuilt() throws Exception {
        if (scenarioLauncherBuilt) {
            return;
        }
        synchronized (ExternalJavaScenarioLauncherProcess.class) {
            if (scenarioLauncherBuilt) {
                return;
            }
            buildReactorModule("integrations/xa-mass-scenario-launcher", "Java scenario launcher");
            scenarioLauncherBuilt = true;
        }
    }

    private static void buildReactorModule(String modulePath, String label) throws Exception {
        Path repoRoot = resolveRepoRoot();
        ProcessBuilder processBuilder = new ProcessBuilder(
                resolveMavenCommand(repoRoot),
                "-q",
                "-pl",
                modulePath,
                "-am",
                "-DskipTests",
                "package");
        processBuilder.directory(repoRoot.toFile());
        processBuilder.redirectErrorStream(true);
        Process buildProcess = processBuilder.start();
        StringBuilder output = new StringBuilder();
        Thread outputPump = new Thread(() -> pumpOutput(buildProcess, output),
                "external-java-scenario-launcher-build-output");
        outputPump.setDaemon(true);
        outputPump.start();
        if (!buildProcess.waitFor(DEFAULT_TIMEOUT.toMillis() * 12, TimeUnit.MILLISECONDS)) {
            buildProcess.destroyForcibly();
            throw new IllegalStateException("Timed out while building " + label);
        }
        outputPump.join(DEFAULT_TIMEOUT.toMillis());
        if (buildProcess.exitValue() != 0) {
            throw new IllegalStateException("Failed to build " + label + ". Output:\n" + output);
        }
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
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (Path cursor = current; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor.resolve("pom.xml"))
                    && Files.exists(cursor.resolve("integrations/xa-mass-scenario-launcher/pom.xml"))) {
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
}
