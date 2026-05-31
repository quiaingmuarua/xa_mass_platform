package com.xa.mass.scenario;

import java.nio.file.Path;
import java.util.Objects;

record ScenarioLauncherOptions(
        String baseUrl,
        String taskApiKey,
        String taskCommandApiKey,
        String workerApiKey,
        String bootstrapKey,
        Path scenarioDir,
        boolean registerOnly,
        boolean help
) {
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8088";
    private static final String DEFAULT_TASK_API_KEY = "crawler-submitter-key";
    private static final String DEFAULT_TASK_COMMAND_API_KEY = "public-probe-ops-key";
    private static final String DEFAULT_BOOTSTRAP_KEY = "dev-bootstrap-key";
    private static final Path DEFAULT_SCENARIO_DIR = Path.of("integrations/samples/dev/scenario");

    static ScenarioLauncherOptions parse(String[] args) {
        String baseUrl = envOrDefault("MASS_BASE_URL", DEFAULT_BASE_URL);
        String taskApiKey = envOrDefault("MASS_TASK_SUBMITTER_KEY", DEFAULT_TASK_API_KEY);
        String taskCommandApiKey = envOrDefault("MASS_TASK_COMMAND_KEY", DEFAULT_TASK_COMMAND_API_KEY);
        String workerApiKey = System.getenv("MASS_WORKER_API_KEY");
        String bootstrapKey = envOrDefault("SAMPLE_BOOTSTRAP_KEY", DEFAULT_BOOTSTRAP_KEY);
        Path scenarioDir = DEFAULT_SCENARIO_DIR;
        boolean registerOnly = false;
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
            } else if ("--register-only".equals(arg)) {
                registerOnly = true;
            } else if ("--base-url".equals(arg)) {
                baseUrl = requiredArg(args, index, arg);
                index++;
            } else if (arg.startsWith("--base-url=")) {
                baseUrl = arg.substring("--base-url=".length());
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
            } else {
                throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        return new ScenarioLauncherOptions(
                normalizeBaseUrl(baseUrl),
                requireNonBlank(taskApiKey, "taskApiKey"),
                requireNonBlank(taskCommandApiKey, "taskCommandApiKey"),
                normalizeOptional(workerApiKey),
                requireNonBlank(bootstrapKey, "bootstrapKey"),
                scenarioDir,
                registerOnly,
                help
        );
    }

    static String helpText() {
        return """
                Usage:
                  java -jar integrations/xa-mass-scenario-launcher/target/xa-mass-scenario-launcher.jar --register-only [options]

                Options:
                  --register-only              Register dev catalog, rules, workers, and tasks, then exit.
                  --base-url <url>             Server HTTP base URL. Default: MASS_BASE_URL or http://127.0.0.1:8088
                  --task-api-key <key>         Default task API key. Default: MASS_TASK_SUBMITTER_KEY or crawler-submitter-key
                  --task-command-api-key <key> Task command API key for seal/approve. Default: MASS_TASK_COMMAND_KEY or public-probe-ops-key
                  --worker-api-key <key>       Optional worker API key override. Default: each worker spec's workerKey
                  --bootstrap-key <key>        Dev bootstrap key. Default: SAMPLE_BOOTSTRAP_KEY or dev-bootstrap-key
                  --scenario-dir <path>        Scenario JSON directory. Default: integrations/samples/dev/scenario
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

    private static String normalizeBaseUrl(String value) {
        String resolved = requireNonBlank(value, "baseUrl");
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
