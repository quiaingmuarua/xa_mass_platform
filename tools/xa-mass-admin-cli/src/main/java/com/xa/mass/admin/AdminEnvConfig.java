package com.xa.mass.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

record AdminEnvConfig(
        ServerConfig server,
        OperatorConfig operator,
        EnvironmentConfig environment,
        CredentialConfig credentials,
        StateConfig state,
        VerifyConfig verify
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    static Loaded load(Path configFile) {
        Objects.requireNonNull(configFile, "configFile is required");
        Path absolute = configFile.toAbsolutePath().normalize();
        try {
            AdminEnvConfig config = MAPPER.readValue(Files.readString(absolute, StandardCharsets.UTF_8),
                    AdminEnvConfig.class);
            Loaded loaded = new Loaded(config, absolute, absolute.getParent());
            loaded.validate();
            return loaded;
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read admin env config: " + absolute, e);
        }
    }

    static ObjectMapper objectMapper() {
        return MAPPER;
    }

    record Loaded(AdminEnvConfig config, Path configFile, Path baseDir) {
        Loaded {
            baseDir = baseDir == null ? Path.of(".").toAbsolutePath().normalize() : baseDir;
        }

        Path resolve(String value, String fieldPath) {
            String normalized = required(value, fieldPath);
            Path path = Path.of(normalized);
            if (!path.isAbsolute()) {
                path = baseDir.resolve(path);
            }
            return path.normalize();
        }

        Path optionalPath(String value, String fieldPath) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return resolve(value, fieldPath);
        }

        void validate() {
            if (config == null) {
                throw new IllegalArgumentException("config is required");
            }
            if (config.server() == null) {
                throw new IllegalArgumentException("server is required");
            }
            required(config.server().baseUrl(), "server.baseUrl");
            if (config.operator() == null) {
                throw new IllegalArgumentException("operator is required");
            }
            required(config.operator().user(), "operator.user");
            if ((config.operator().passwordEnv() == null || config.operator().passwordEnv().isBlank())
                    && (config.operator().passwordFile() == null || config.operator().passwordFile().isBlank())) {
                throw new IllegalArgumentException("operator.passwordEnv or operator.passwordFile is required");
            }
            if (config.environment() == null) {
                throw new IllegalArgumentException("environment is required");
            }
            if (config.environment().mode() == null) {
                throw new IllegalArgumentException("environment.mode is required");
            }
            required(config.environment().catalogManifest(), "environment.catalogManifest");
            required(config.environment().rulesManifest(), "environment.rulesManifest");
            if (config.credentials() == null) {
                throw new IllegalArgumentException("credentials is required");
            }
            validateTaskCredential(config.credentials().taskCredential());
            validateWorkerCredentials(config.credentials().workerCredentials());
            if (config.state() == null) {
                throw new IllegalArgumentException("state is required");
            }
            if (config.state().mode() == null) {
                throw new IllegalArgumentException("state.mode is required");
            }
            if (config.state().mode() == EnvStateMode.FILE) {
                required(config.state().markerFile(), "state.markerFile");
            }
            if (config.verify() == null) {
                throw new IllegalArgumentException("verify is required");
            }
        }

        private void validateTaskCredential(TaskCredentialConfig task) {
            if (task == null) {
                throw new IllegalArgumentException("credentials.taskCredential is required");
            }
            required(task.apiKeyFile(), "credentials.taskCredential.apiKeyFile");
            required(task.principalId(), "credentials.taskCredential.principalId");
            required(task.createdForUserId(), "credentials.taskCredential.createdForUserId");
            nonEmpty(task.permissions(), "credentials.taskCredential.permissions");
            nonEmpty(task.projectScopes(), "credentials.taskCredential.projectScopes");
            nonEmpty(task.eventScopes(), "credentials.taskCredential.eventScopes");
            if ((task.rawSecretFile() == null || task.rawSecretFile().isBlank())
                    && (task.rawSecretEnv() == null || task.rawSecretEnv().isBlank())) {
                throw new IllegalArgumentException(
                        "credentials.taskCredential.rawSecretFile or rawSecretEnv is required");
            }
        }

        private void validateWorkerCredentials(WorkerCredentialPolicyConfig policy) {
            if (policy == null) {
                throw new IllegalArgumentException("credentials.workerCredentials is required");
            }
            required(policy.workerSpecFile(), "credentials.workerCredentials.workerSpecFile");
            required(policy.principalIdTemplate(), "credentials.workerCredentials.principalIdTemplate");
            required(policy.createdForUserId(), "credentials.workerCredentials.createdForUserId");
            nonEmpty(policy.permissions(), "credentials.workerCredentials.permissions");
            required(policy.rawSecretSource(), "credentials.workerCredentials.rawSecretSource");
            required(policy.workerIdAttribute(), "credentials.workerCredentials.workerIdAttribute");
            if (!policy.deriveProjectScopesFromWorkerBindings() && policy.projectScopes().isEmpty()) {
                throw new IllegalArgumentException(
                        "credentials.workerCredentials.projectScopes or projectScopesFromWorkerBindings is required");
            }
            if (!policy.deriveEventScopesFromWorkerBindings() && policy.eventScopes().isEmpty()) {
                throw new IllegalArgumentException(
                        "credentials.workerCredentials.eventScopes or eventScopesFromWorkerBindings is required");
            }
        }

        String operatorPassword() {
            OperatorConfig operator = config.operator();
            if (operator.passwordEnv() != null && !operator.passwordEnv().isBlank()) {
                String value = System.getenv(operator.passwordEnv().trim());
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            if (operator.passwordFile() != null && !operator.passwordFile().isBlank()) {
                try {
                    return Files.readString(resolve(operator.passwordFile(), "operator.passwordFile"),
                            StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new IllegalArgumentException("failed to read operator.passwordFile", e);
                }
            }
            throw new IllegalArgumentException("operator password is not available from configured source");
        }

        String taskRawSecret() {
            TaskCredentialConfig task = config.credentials().taskCredential();
            if (task.rawSecretEnv() != null && !task.rawSecretEnv().isBlank()) {
                String value = System.getenv(task.rawSecretEnv().trim());
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            Path rawSecretFile = resolve(task.rawSecretFile(), "credentials.taskCredential.rawSecretFile");
            try {
                if (Files.exists(rawSecretFile)) {
                    String value = Files.readString(rawSecretFile, StandardCharsets.UTF_8).trim();
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to read task raw secret file: " + rawSecretFile, e);
            }
            return null;
        }
    }

    record ServerConfig(String baseUrl,
                        String profile,
                        Integer connectTimeoutSeconds,
                        Integer requestTimeoutSeconds) {
        Duration connectTimeout() {
            return Duration.ofSeconds(connectTimeoutSeconds == null ? 5 : connectTimeoutSeconds);
        }

        Duration requestTimeout() {
            return Duration.ofSeconds(requestTimeoutSeconds == null ? 30 : requestTimeoutSeconds);
        }
    }

    record OperatorConfig(String user, String passwordEnv, String passwordFile) {
    }

    record EnvironmentConfig(EnvInitMode mode, String catalogManifest, String rulesManifest) {
    }

    record CredentialConfig(TaskCredentialConfig taskCredential,
                            WorkerCredentialPolicyConfig workerCredentials) {
    }

    record TaskCredentialConfig(String apiKeyFile,
                                String principalId,
                                String createdForUserId,
                                List<String> permissions,
                                List<String> projectScopes,
                                List<String> eventScopes,
                                String rawSecretFile,
                                String rawSecretEnv,
                                Map<String, String> attributes) {
        TaskCredentialConfig {
            permissions = copyList(permissions);
            projectScopes = copyList(projectScopes);
            eventScopes = copyList(eventScopes);
            attributes = copyMap(attributes);
        }
    }

    record WorkerCredentialPolicyConfig(String workerSpecFile,
                                        String principalIdTemplate,
                                        String createdForUserId,
                                        List<String> permissions,
                                        List<String> projectScopes,
                                        List<String> eventScopes,
                                        Boolean projectScopesFromWorkerBindings,
                                        Boolean eventScopesFromWorkerBindings,
                                        String rawSecretSource,
                                        String workerIdAttribute,
                                        Integer maxWorkers) {
        WorkerCredentialPolicyConfig {
            permissions = copyList(permissions);
            projectScopes = copyList(projectScopes);
            eventScopes = copyList(eventScopes);
        }

        boolean deriveProjectScopesFromWorkerBindings() {
            return Boolean.TRUE.equals(projectScopesFromWorkerBindings);
        }

        boolean deriveEventScopesFromWorkerBindings() {
            return Boolean.TRUE.equals(eventScopesFromWorkerBindings);
        }
    }

    record StateConfig(EnvStateMode mode, String markerFile) {
    }

    record VerifyConfig(List<String> requiredProjects, List<String> requiredEvents) {
        VerifyConfig {
            requiredProjects = copyList(requiredProjects);
            requiredEvents = copyList(requiredEvents);
        }
    }

    enum EnvInitMode {
        VERIFY,
        APPLY,
        APPLY_IF_EMPTY,
        RESET_AND_APPLY;

        @JsonCreator
        static EnvInitMode parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return EnvInitMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    enum EnvStateMode {
        MEMORY,
        FILE;

        @JsonCreator
        static EnvStateMode parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return EnvStateMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    static String required(String value, String fieldPath) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldPath + " is required");
        }
        return value.trim();
    }

    static void nonEmpty(List<String> values, String fieldPath) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldPath + " is required");
        }
    }

    private static List<String> copyList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey().trim(), entry.getValue().trim()),
                        LinkedHashMap::putAll);
    }
}
