package com.xa.mass.scenario;

import java.nio.file.Path;
import java.net.URI;
import java.util.Objects;

record ScenarioLauncherOptions(
        String baseUrl,
        URI webSocketUrl,
        String taskApiKey,
        String taskCommandApiKey,
        String workerApiKey,
        String bootstrapKey,
        boolean devBootstrapEnabled,
        Path scenarioDir,
        long idleTimeoutMs,
        int maxPollingWorkers,
        boolean registerOnly,
        boolean help
) {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8088";
    private static final String DEFAULT_TASK_API_KEY = "crawler-submitter-key";
    private static final String DEFAULT_TASK_COMMAND_API_KEY = "public-probe-ops-key";
    private static final String DEFAULT_BOOTSTRAP_KEY = "dev-bootstrap-key";
    private static final Path DEFAULT_SCENARIO_DIR = Path.of("integrations/samples/dev/scenario");
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 60_000L;
    private static final int DEFAULT_MAX_POLLING_WORKERS = 25;

    static ScenarioLauncherOptions parse(String[] args) {
        String baseUrl = envOrDefault("MASS_BASE_URL", DEFAULT_BASE_URL);
        URI webSocketUrl = optionalUri(System.getenv("MASS_WEBSOCKET_URL"), "MASS_WEBSOCKET_URL");
        String taskApiKey = envOrDefault("MASS_TASK_SUBMITTER_KEY", DEFAULT_TASK_API_KEY);
        String taskCommandApiKey = envOrDefault("MASS_TASK_COMMAND_KEY", DEFAULT_TASK_COMMAND_API_KEY);
        String workerApiKey = System.getenv("MASS_WORKER_API_KEY");
        String bootstrapKey = envOrDefault("SAMPLE_BOOTSTRAP_KEY", DEFAULT_BOOTSTRAP_KEY);
        boolean devBootstrapEnabled = booleanEnvOrDefault("MASS_SCENARIO_DEV_BOOTSTRAP", true);
        Path scenarioDir = DEFAULT_SCENARIO_DIR;
        long idleTimeoutMs = longEnvOrDefault("MASS_SCENARIO_IDLE_TIMEOUT_MS", DEFAULT_IDLE_TIMEOUT_MS);
        int maxPollingWorkers = intEnvOrDefault("MASS_SCENARIO_MAX_POLLING_WORKERS", DEFAULT_MAX_POLLING_WORKERS);
        boolean registerOnly = false;
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--register-only".equals(arg)) {
                registerOnly = true;
            } else if ("--skip-dev-bootstrap".equals(arg)) {
                devBootstrapEnabled = false;
            } else if ("--dev-bootstrap".equals(arg)) {
                devBootstrapEnabled = true;
            } else if (arg.startsWith("--dev-bootstrap=")) {
                devBootstrapEnabled = parseBoolean(arg.substring("--dev-bootstrap=".length()), "--dev-bootstrap");
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
            } else if ("--task-command-api-key".equals(arg)) {
                taskCommandApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--task-command-api-key=")) {
                taskCommandApiKey = arg.substring("--task-command-api-key=".length());
            } else if ("--worker-api-key".equals(arg)) {
                workerApiKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--worker-api-key=")) {
                workerApiKey = arg.substring("--worker-api-key=".length());
            } else if ("--bootstrap-key".equals(arg)) {
                bootstrapKey = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--bootstrap-key=")) {
                bootstrapKey = arg.substring("--bootstrap-key=".length());
            } else if ("--scenario-dir".equals(arg)) {
                scenarioDir = Path.of(requiredArg(args, index, arg));
                index++;
            } else if (arg.startsWith("--scenario-dir=")) {
                scenarioDir = Path.of(arg.substring("--scenario-dir=".length()));
            } else if ("--idle-timeout-ms".equals(arg)) {
                idleTimeoutMs = parseLong(requiredArg(args, index, arg), arg);
                index++;
            } else if (arg.startsWith("--idle-timeout-ms=")) {
                idleTimeoutMs = parseLong(arg.substring("--idle-timeout-ms=".length()), "--idle-timeout-ms");
            } else if ("--max-polling-workers".equals(arg)) {
                maxPollingWorkers = parseInt(requiredArg(args, index, arg), arg);
                index++;
            } else if (arg.startsWith("--max-polling-workers=")) {
                maxPollingWorkers = parseInt(arg.substring("--max-polling-workers=".length()), "--max-polling-workers");
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be >= 0");
        }
        if (maxPollingWorkers < 0) {
            throw new IllegalArgumentException("maxPollingWorkers must be >= 0");
        }
        return new ScenarioLauncherOptions(
                normalizeBaseUrl(baseUrl),
                webSocketUrl,
                requireNonBlank(taskApiKey, "taskApiKey"),
                requireNonBlank(taskCommandApiKey, "taskCommandApiKey"),
                normalizeOptional(workerApiKey),
                requireNonBlank(bootstrapKey, "bootstrapKey"),
                devBootstrapEnabled,
                scenarioDir,
                idleTimeoutMs,
                maxPollingWorkers,
                registerOnly,
                help
        );
    }

    static String helpText() {
        return """
                Usage:
                  java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-launcher.jar [options]

                Options:
                  --register-only              Register dev catalog, rules, workers, and tasks, then exit without polling workers.
                  --base-url <url>             Server HTTP base URL. Default: MASS_BASE_URL or http://127.0.0.1:8088
                  --websocket-url <url>        Optional server WebSocket URL for realtime launcher workers. Default: MASS_WEBSOCKET_URL
                  --task-api-key <key>         Default task API key. Default: MASS_TASK_SUBMITTER_KEY or crawler-submitter-key
                  --task-command-api-key <key> Task command API key for seal/approve. Default: MASS_TASK_COMMAND_KEY or public-probe-ops-key
                  --worker-api-key <key>       Optional worker API key override. Default: each worker spec's workerKey
                  --bootstrap-key <key>        Dev bootstrap key. Default: SAMPLE_BOOTSTRAP_KEY or dev-bootstrap-key
                  --skip-dev-bootstrap         Do not call sample-only /sample-api/bootstrap/** endpoints; use pre-created catalog/rules.
                  --dev-bootstrap[=true|false] Enable or disable dev bootstrap explicitly. Default: MASS_SCENARIO_DEV_BOOTSTRAP or true
                  --scenario-dir <path>        Scenario JSON directory. Default: integrations/samples/dev/scenario
                  --idle-timeout-ms <ms>       Exit after this much continuous idle time in launch mode. Default: 60000. Use 0 to disable.
                  --max-polling-workers <n>    Max polling workers to start in launch mode. Default: 25. Use 0 for no cap.
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

    private static long longEnvOrDefault(String name, long defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : parseLong(value, name);
    }

    private static int intEnvOrDefault(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : parseInt(value, name);
    }

    private static boolean booleanEnvOrDefault(String name, boolean defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : parseBoolean(value, name);
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false: " + value);
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number: " + value, e);
        }
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
