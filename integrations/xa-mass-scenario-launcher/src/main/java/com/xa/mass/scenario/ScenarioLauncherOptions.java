package com.xa.mass.scenario;

import java.nio.file.Path;
import java.net.URI;
import java.util.Objects;

record ScenarioLauncherOptions(
        String baseUrl,
        URI webSocketUrl,
        String taskApiKey,
        String workerApiKey,
        Path scenarioDir,
        int maxPollingWorkers,
        boolean help
) {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8088";
    private static final String DEFAULT_TASK_API_KEY = "crawler-task-api-key";
    private static final Path DEFAULT_SCENARIO_DIR = Path.of("integrations/samples/dev/scenario");
    private static final int DEFAULT_MAX_POLLING_WORKERS = 25;

    static ScenarioLauncherOptions parse(String[] args) {
        String baseUrl = envOrDefault("MASS_BASE_URL", DEFAULT_BASE_URL);
        URI webSocketUrl = optionalUri(System.getenv("MASS_WEBSOCKET_URL"), "MASS_WEBSOCKET_URL");
        String taskApiKey = envOrDefault("MASS_TASK_API_KEY", DEFAULT_TASK_API_KEY);
        String workerApiKey = System.getenv("MASS_WORKER_API_KEY");
        Path scenarioDir = DEFAULT_SCENARIO_DIR;
        int maxPollingWorkers = intEnvOrDefault("MASS_SCENARIO_MAX_POLLING_WORKERS", DEFAULT_MAX_POLLING_WORKERS);
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--base-url".equals(arg)) {
                baseUrl = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--base-url=")) {
                baseUrl = arg.substring("--base-url=".length());
            } else if ("--websocket-url".equals(arg)) {
                webSocketUrl = optionalUri(requiredArg(args, index, arg), arg);
                index++;
            } else if (arg.startsWith("--websocket-url=")) {
                webSocketUrl = optionalUri(arg.substring("--websocket-url=".length()), "--websocket-url");
            } else if ("--task-api-key".equals(arg)) {
                taskApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--task-api-key=")) {
                taskApiKey = arg.substring("--task-api-key=".length());
            } else if ("--worker-api-key".equals(arg)) {
                workerApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--worker-api-key=")) {
                workerApiKey = arg.substring("--worker-api-key=".length());
            } else if ("--scenario-dir".equals(arg)) {
                scenarioDir = Path.of(requiredArg(args, index, arg));
                index++;
            } else if (arg.startsWith("--scenario-dir=")) {
                scenarioDir = Path.of(arg.substring("--scenario-dir=".length()));
            } else if ("--max-polling-workers".equals(arg)) {
                maxPollingWorkers = parseInt(requiredArg(args, index, arg), arg);
                index++;
            } else if (arg.startsWith("--max-polling-workers=")) {
                maxPollingWorkers = parseInt(arg.substring("--max-polling-workers=".length()), "--max-polling-workers");
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        if (maxPollingWorkers < 0) {
            throw new IllegalArgumentException("maxPollingWorkers must be >= 0");
        }
        return new ScenarioLauncherOptions(
                normalizeBaseUrl(baseUrl),
                webSocketUrl,
                requireNonBlank(taskApiKey, "taskApiKey"),
                normalizeOptional(workerApiKey),
                scenarioDir,
                maxPollingWorkers,
                help
        );
    }

    static String taskHelpText() {
        return """
                Usage:
                  java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar [options]

                Starts the task-producer side only: create scenario tasks and
                append items according to tasks.json.

                Options:
                %s
                """.formatted(commonOptionsText());
    }

    static String workerHelpText() {
        return """
                Usage:
                  java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-worker-launcher.jar [options]

                Starts the worker side only: register worker topology and start
                Java SDK worker sessions according to workers.json.

                Options:
                %s
                """.formatted(commonOptionsText());
    }

    static String commonOptionsText() {
        return """
                  --base-url <url>             Server HTTP base URL. Default: MASS_BASE_URL or http://127.0.0.1:8088
                  --websocket-url <url>        Optional server WebSocket URL for realtime launcher workers. Default: MASS_WEBSOCKET_URL
                  --task-api-key <key>         Default task API key. Default: MASS_TASK_API_KEY or crawler-task-api-key
                  --worker-api-key <key>       Optional worker API key override. Default: each worker spec's workerKey
                  --scenario-dir <path>        Scenario JSON directory. Default: integrations/samples/dev/scenario
                  --max-polling-workers <n>    Max polling workers to start in worker launcher. Default: 25. Use 0 for no cap.
                  -h, --help                   Show this help.
                """;
    }

    private static String requiredArg(String[] args, int index, String name) {
        if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
            throw new IllegalArgumentException(name + " requires a value");
        }
        return args[index + 1];
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnvOrDefault(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : parseInt(value, name);
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number: " + value, e);
        }
    }

    private static String normalizeBaseUrl(String value) {
        String resolved = requireNonBlank(value, "baseUrl");
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static URI optionalUri(String value, String name) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        try {
            return URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be a URI: " + value, e);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
