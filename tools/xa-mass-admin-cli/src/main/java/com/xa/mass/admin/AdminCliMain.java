package com.xa.mass.admin;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;

public final class AdminCliMain {
    private AdminCliMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.out.println(helpText());
            return;
        }
        ObjectMapper objectMapper = AdminEnvConfig.objectMapper();
        switch (args[0]) {
            case "health" -> health(commandArgs(args), objectMapper);
            case "auth" -> auth(commandArgs(args), objectMapper);
            case "api" -> api(commandArgs(args), objectMapper);
            case "api-key" -> apiKey(commandArgs(args), objectMapper);
            case "task" -> task(commandArgs(args), objectMapper);
            case "env" -> env(commandArgs(args), objectMapper);
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }

    private static void health(String[] args, ObjectMapper objectMapper) {
        BaseOptions options = BaseOptions.parse(args);
        new AdminHttpClient(
                options.baseUrl(),
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30),
                objectMapper
        ).health();
        System.out.println("{\"status\":\"ok\"}");
    }

    private static void auth(String[] args, ObjectMapper objectMapper) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("auth subcommand is required");
        }
        BaseOptions options = BaseOptions.parse(commandArgs(args));
        AdminHttpClient client = new AdminHttpClient(
                options.baseUrl(),
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30),
                objectMapper
        );
        switch (args[0]) {
            case "config" -> {
                AuthConfig config = client.authConfig();
                java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
                output.put("authMode", config.authMode());
                output.put("operatorHeaderSupported", config.operatorHeaderSupported());
                output.put("sessionCookieSupported", config.sessionCookieSupported());
                output.put("csrfHeaderName", config.csrfHeaderName());
                System.out.println(objectMapper.writeValueAsString(output));
            }
            case "login" -> {
                String user = option(commandArgs(args), "--operator-user", "ops-admin");
                String password = option(commandArgs(args), "--operator-password", null);
                if (password == null || password.isBlank()) {
                    throw new IllegalArgumentException("--operator-password is required");
                }
                client.authConfig();
                client.login(user, password);
                client.requireCurrentOperator();
                System.out.println("{\"status\":\"authenticated\"}");
            }
            default -> throw new IllegalArgumentException("unknown auth subcommand: " + args[0]);
        }
    }

    private static void apiKey(String[] args, ObjectMapper objectMapper) throws Exception {
        if (args.length == 0 || !"current".equals(args[0])) {
            throw new IllegalArgumentException("supported api-key subcommand: current");
        }
        BaseOptions options = BaseOptions.parse(commandArgs(args));
        String rawSecret = option(commandArgs(args), "--api-key", null);
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalArgumentException("--api-key is required");
        }
        AdminHttpClient client = new AdminHttpClient(
                options.baseUrl(),
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30),
                objectMapper
        );
        CurrentApiKey current = client.currentApiKey(rawSecret);
        if (current == null) {
            throw new IllegalStateException("API key is not current");
        }
        System.out.println(objectMapper.writeValueAsString(java.util.Map.of(
                "principalId", current.principalId(),
                "createdForUserId", current.createdForUserId()
        )));
    }

    private static void api(String[] args, ObjectMapper objectMapper) throws Exception {
        if (args.length == 0 || !"health".equals(args[0])) {
            throw new IllegalArgumentException("supported api subcommand: health");
        }
        String configPath = option(commandArgs(args), "--config", null);
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("--config is required");
        }
        AdminEnvConfig.Loaded loaded = AdminEnvConfig.load(Path.of(configPath));
        AdminApiHealthService service = new AdminApiHealthService(objectMapper, Clock.systemUTC());
        try {
            System.out.println(objectMapper.writeValueAsString(service.health(loaded)));
        } catch (ApiHealthFailure failure) {
            System.out.println(objectMapper.writeValueAsString(failure.report()));
            throw failure;
        }
    }

    private static void task(String[] args, ObjectMapper objectMapper) throws Exception {
        if (args.length == 0 || !"command".equals(args[0])) {
            throw new IllegalArgumentException("supported task subcommand: command");
        }
        String[] commandArgs = commandArgs(args);
        BaseOptions options = BaseOptions.parse(commandArgs);
        String user = option(commandArgs, "--operator-user", "ops-admin");
        String password = option(commandArgs, "--operator-password", null);
        String taskId = option(commandArgs, "--task-id", null);
        String command = option(commandArgs, "--command", null);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("--operator-password is required");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("--task-id is required");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("--command is required");
        }
        AdminHttpClient client = new AdminHttpClient(
                options.baseUrl(),
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30),
                objectMapper
        );
        client.authConfig();
        client.login(user, password);
        client.requireCurrentOperator();
        System.out.println(objectMapper.writeValueAsString(client.executeTaskCommand(taskId, command)));
    }

    private static void env(String[] args, ObjectMapper objectMapper) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("env subcommand is required");
        }
        String configPath = option(commandArgs(args), "--config", null);
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("--config is required");
        }
        AdminEnvConfig.Loaded loaded = AdminEnvConfig.load(Path.of(configPath));
        AdminEnvService service = new AdminEnvService(objectMapper, Clock.systemUTC());
        AdminEnvReport report = switch (args[0]) {
            case "verify" -> service.verify(loaded);
            case "init" -> service.init(loaded);
            default -> throw new IllegalArgumentException("unknown env subcommand: " + args[0]);
        };
        System.out.println(objectMapper.writeValueAsString(report));
    }

    private static String[] commandArgs(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static String option(String[] args, String name, String defaultValue) {
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (arg.equals(name)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(name + " requires a value");
                }
                return args[index + 1];
            }
            if (arg.startsWith(name + "=")) {
                return arg.substring((name + "=").length());
            }
        }
        return defaultValue;
    }

    private static String helpText() {
        return """
                Usage:
                  xa-mass-admin health --base-url http://127.0.0.1:8088
                  xa-mass-admin auth config --base-url http://127.0.0.1:8088
                  xa-mass-admin auth login --base-url http://127.0.0.1:8088 --operator-user ops-admin --operator-password secret
                  xa-mass-admin api-key current --base-url http://127.0.0.1:8088 --api-key <raw-secret>
                  xa-mass-admin task command --base-url http://127.0.0.1:8088 --operator-user ops-admin --operator-password secret --task-id <task-id> --command APPROVE
                  xa-mass-admin env verify --config <admin-env.json>
                  xa-mass-admin env init --config <admin-env.json>
                  xa-mass-admin api health --config <admin-env.json>
                """;
    }

    private record BaseOptions(String baseUrl) {
        static BaseOptions parse(String[] args) {
            return new BaseOptions(option(args, "--base-url", "http://127.0.0.1:8088"));
        }
    }
}
