package com.xa.mass.scenario;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

record ScenarioLauncherOptions(
        String baseUrl,
        URI webSocketUrl,
        String taskApiKey,
        String workerApiKey,
        Path scenarioDir,
        Path configPath,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxPollingWorkers,
        boolean help
) {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8088";
    private static final String DEFAULT_TASK_API_KEY = "crawler-task-api-key";
    private static final Path DEFAULT_SCENARIO_DIR = Path.of("integrations/samples/dev/scenario");
    private static final int DEFAULT_MAX_POLLING_WORKERS = 25;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    static ScenarioLauncherOptions parse(String[] args) {
        return parseTask(args);
    }

    static ScenarioLauncherOptions parseTask(String[] args) {
        return parse(args, true);
    }

    static ScenarioLauncherOptions parseWorker(String[] args) {
        return parse(args, false);
    }

    private static ScenarioLauncherOptions parse(String[] args, boolean loadTaskConfig) {
        String cliBaseUrl = null;
        String cliWebSocketUrl = null;
        String cliTaskApiKey = null;
        String cliWorkerApiKey = null;
        Path cliWorkerApiKeyFile = null;
        Path cliScenarioDir = null;
        Path configPath = null;
        Integer cliMaxPollingWorkers = null;
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--config".equals(arg)) {
                configPath = Path.of(requiredArg(args, index, arg));
                index++;
            } else if (arg.startsWith("--config=")) {
                configPath = Path.of(arg.substring("--config=".length()));
            } else if ("--base-url".equals(arg)) {
                cliBaseUrl = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--base-url=")) {
                cliBaseUrl = arg.substring("--base-url=".length());
            } else if ("--websocket-url".equals(arg)) {
                cliWebSocketUrl = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--websocket-url=")) {
                cliWebSocketUrl = arg.substring("--websocket-url=".length());
            } else if ("--task-api-key".equals(arg)) {
                cliTaskApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--task-api-key=")) {
                cliTaskApiKey = arg.substring("--task-api-key=".length());
            } else if ("--worker-api-key".equals(arg)) {
                cliWorkerApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--worker-api-key=")) {
                cliWorkerApiKey = arg.substring("--worker-api-key=".length());
            } else if ("--worker-api-key-file".equals(arg)) {
                cliWorkerApiKeyFile = Path.of(requiredArg(args, index, arg));
                index++;
            } else if (arg.startsWith("--worker-api-key-file=")) {
                cliWorkerApiKeyFile = Path.of(arg.substring("--worker-api-key-file=".length()));
            } else if ("--scenario-dir".equals(arg)) {
                cliScenarioDir = Path.of(requiredArg(args, index, arg));
                index++;
            } else if (arg.startsWith("--scenario-dir=")) {
                cliScenarioDir = Path.of(arg.substring("--scenario-dir=".length()));
            } else if ("--max-polling-workers".equals(arg)) {
                cliMaxPollingWorkers = parseInt(requiredArg(args, index, arg), arg);
                index++;
            } else if (arg.startsWith("--max-polling-workers=")) {
                cliMaxPollingWorkers = parseInt(arg.substring("--max-polling-workers=".length()), "--max-polling-workers");
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }

        ScenarioLauncherConfig.Loaded loadedConfig = loadTaskConfig && !help ? loadConfig(configPath) : null;
        ScenarioLauncherConfig config = loadedConfig == null ? null : loadedConfig.config();
        ScenarioLauncherConfig.ServerConfig serverConfig = config == null ? null : config.server();
        ScenarioLauncherConfig.CredentialsConfig credentialsConfig = config == null ? null : config.credentials();

        String baseUrl = firstNonBlank(
                cliBaseUrl,
                System.getenv("MASS_BASE_URL"),
                serverConfig == null ? null : serverConfig.baseUrl(),
                DEFAULT_BASE_URL
        );
        URI webSocketUrl = optionalUri(firstNonBlank(
                cliWebSocketUrl,
                System.getenv("MASS_WEBSOCKET_URL"),
                null
        ), "webSocketUrl");
        String taskApiKey = firstNonBlank(
                cliTaskApiKey,
                System.getenv("MASS_TASK_API_KEY"),
                taskApiKeyFromConfig(loadedConfig, credentialsConfig),
                DEFAULT_TASK_API_KEY
        );
        String workerApiKey = firstNonBlank(
                cliWorkerApiKey,
                System.getenv("MASS_WORKER_API_KEY"),
                workerApiKeyFromFile(cliWorkerApiKeyFile),
                workerApiKeyFromFile(defaultWorkerApiKeyFile()),
                null
        );
        Path scenarioDir = cliScenarioDir == null ? DEFAULT_SCENARIO_DIR : cliScenarioDir;
        int maxPollingWorkers = cliMaxPollingWorkers != null
                ? cliMaxPollingWorkers
                : intEnvOrDefault("MASS_SCENARIO_MAX_POLLING_WORKERS", DEFAULT_MAX_POLLING_WORKERS);
        Duration connectTimeout = durationFromSeconds(
                serverConfig == null ? null : serverConfig.connectTimeoutSeconds(),
                DEFAULT_CONNECT_TIMEOUT,
                "server.connectTimeoutSeconds"
        );
        Duration requestTimeout = durationFromSeconds(
                serverConfig == null ? null : serverConfig.requestTimeoutSeconds(),
                DEFAULT_REQUEST_TIMEOUT,
                "server.requestTimeoutSeconds"
        );

        if (maxPollingWorkers < 0) {
            throw new IllegalArgumentException("maxPollingWorkers must be >= 0");
        }
        return new ScenarioLauncherOptions(
                normalizeBaseUrl(baseUrl),
                webSocketUrl,
                requireNonBlank(taskApiKey, "taskApiKey"),
                normalizeOptional(workerApiKey),
                scenarioDir,
                configPath,
                connectTimeout,
                requestTimeout,
                maxPollingWorkers,
                help
        );
    }

    static String taskHelpText() {
        return """
                Usage:
                  java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-task-launcher.jar [options]

                Starts the task-producer side only: create scenario tasks and
                append items according to a task config file or tasks.json.

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
                  --config <path>              Task launcher config file. Worker config is deferred.
                  --websocket-url <url>        Optional server WebSocket URL for realtime launcher workers. Default: MASS_WEBSOCKET_URL
                  --task-api-key <key>         Default task API key. Default: MASS_TASK_API_KEY or crawler-task-api-key
                  --worker-api-key <key>       Optional worker API key override. Default: each worker spec's workerKey
                  --worker-api-key-file <path> Optional worker API key cache file. Default: examples/secrets/worker-api-key.txt when present
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

    private static ScenarioLauncherConfig.Loaded loadConfig(Path configPath) {
        if (configPath == null) {
            return null;
        }
        try {
            return ScenarioLauncherConfig.load(configPath, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("failed to read config file: " + configPath, e);
        }
    }

    private static String taskApiKeyFromConfig(ScenarioLauncherConfig.Loaded loadedConfig,
                                               ScenarioLauncherConfig.CredentialsConfig credentialsConfig) {
        if (loadedConfig == null || credentialsConfig == null) {
            return null;
        }
        String direct = normalizeOptional(credentialsConfig.taskApiKey());
        if (direct != null) {
            return direct;
        }
        String file = normalizeOptional(credentialsConfig.taskApiKeyFile());
        if (file == null) {
            return null;
        }
        Path resolved = loadedConfig.resolvePath(file, "credentials.taskApiKeyFile");
        try {
            String value = java.nio.file.Files.readString(resolved).trim();
            if (value.isBlank()) {
                throw new IllegalArgumentException("credentials.taskApiKeyFile is blank: " + resolved);
            }
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("failed to read credentials.taskApiKeyFile: " + resolved, e);
        }
    }

    private static String workerApiKeyFromFile(Path path) {
        if (path == null || !java.nio.file.Files.exists(path)) {
            return null;
        }
        try {
            String value = java.nio.file.Files.readString(path).trim();
            if (value.isBlank()) {
                throw new IllegalArgumentException("worker API key file is blank: " + path);
            }
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("failed to read worker API key file: " + path, e);
        }
    }

    private static Path defaultWorkerApiKeyFile() {
        return Path.of("integrations/xa-mass-scenario-launcher/examples/secrets/worker-api-key.txt");
    }

    private static Duration durationFromSeconds(Integer seconds, Duration defaultValue, String fieldName) {
        if (seconds == null) {
            return defaultValue;
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return Duration.ofSeconds(seconds);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalizeOptional(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
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
