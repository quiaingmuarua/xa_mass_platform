package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit consumer coordinates for the existing open-loop lane; no Server lifecycle authority. */
final class DeploymentConfig {
    private static final Set<String> FIELDS = Set.of("runtimeUrl", "workerRuntimeUrl", "project", "endpointManager",
            "groups", "workersPerGroup", "consumerId", "accessKeyFile", "gatewayCredentialFile", "hostMaxHeap",
            "hostFdLimit", "assignmentBatchLimit", "serverDeployment");
    private final Map<String, Object> values;

    private DeploymentConfig(Path source, Map<String, Object> input) {
        if (!FIELDS.containsAll(input.keySet())) throw new IllegalArgumentException("Unknown deployment configuration field");
        var resolved = new LinkedHashMap<String, Object>();
        for (String field : List.of("runtimeUrl", "workerRuntimeUrl")) resolved.put(field, https(input, field));
        for (String field : List.of("project", "endpointManager", "consumerId")) resolved.put(field, identity(input, field));
        if (!(input.get("groups") instanceof List<?> rawGroups) || rawGroups.size() != 2)
            throw new IllegalArgumentException("Deployment requires exactly two Groups");
        var groups = rawGroups.stream().map(value -> {
            if (!(value instanceof String text) || !validIdentity(text))
                throw new IllegalArgumentException("Invalid deployment Group");
            return text;
        }).toList();
        if (Set.copyOf(groups).size() != 2) throw new IllegalArgumentException("Deployment Groups must be distinct");
        resolved.put("groups", groups);
        resolved.put("workersPerGroup", ExperimentConfig.integer(input, "workersPerGroup", 1, 15_000));
        resolved.put("assignmentBatchLimit", ExperimentConfig.integer(input, "assignmentBatchLimit", 1, 1_000));
        resolved.put("hostFdLimit", integerOrDefault(input, "hostFdLimit", 32_768, 1_024, 1_048_576));
        Object heap = input.getOrDefault("hostMaxHeap", "3g");
        if (!(heap instanceof String text) || !text.matches("[1-9][0-9]*[mMgG]"))
            throw new IllegalArgumentException("Invalid deployment hostMaxHeap");
        resolved.put("hostMaxHeap", heap);
        for (String field : List.of("accessKeyFile", "gatewayCredentialFile")) {
            Path file = source.getParent().resolve(text(input, field)).normalize().toAbsolutePath();
            if (!Files.isRegularFile(file) || !Files.isReadable(file))
                throw new IllegalArgumentException("Deployment " + field + " must be a readable file");
            resolved.put(field, file.toString());
        }
        var server = CallApi.object(input.get("serverDeployment"));
        if (!server.keySet().equals(Set.of("version", "gitCommit", "configurationId")))
            throw new IllegalArgumentException("Deployment requires Server version, gitCommit and configurationId");
        var provenance = new LinkedHashMap<String, Object>();
        for (String field : List.of("version", "gitCommit", "configurationId")) provenance.put(field, text(server, field));
        resolved.put("serverDeployment", Map.copyOf(provenance));
        values = java.util.Collections.unmodifiableMap(resolved);
    }

    static DeploymentConfig read(String path) throws Exception {
        Path source = Path.of(path).toAbsolutePath().normalize();
        return new DeploymentConfig(source, Jsons.parseObject(Files.readString(source)));
    }

    static DeploymentConfig from(Map<String, String> options) throws Exception {
        if (!options.containsKey("--deployment-config")) return null;
        if (options.containsKey("--runtime-url") || options.containsKey("--experiment-config"))
            throw new IllegalArgumentException("Deployment coordinates cannot be combined with local Runtime or experiment overrides");
        return read(options.get("--deployment-config"));
    }

    static void resolve(Map<String, String> options) throws Exception {
        var config = from(options);
        if (config == null) throw new IllegalArgumentException("deployment-config phase requires --deployment-config");
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        Files.writeString(output.resolve("deployment.json"), Jsons.toJson(config.resolved()), StandardOpenOption.CREATE_NEW);
    }

    Map<String, Object> resolved() {
        var result = new LinkedHashMap<>(values);
        var tasks = new LinkedHashMap<String, String>();
        groups().forEach(group -> tasks.put(group, rpcTaskId(group)));
        result.put("rpcTaskIds", tasks);
        return result;
    }

    String runtimeUrl() { return (String) values.get("runtimeUrl"); }
    String project() { return (String) values.get("project"); }
    String endpointManager() { return (String) values.get("endpointManager"); }
    @SuppressWarnings("unchecked") List<String> groups() { return (List<String>) values.get("groups"); }
    int workersPerGroup() { return (Integer) values.get("workersPerGroup"); }
    int assignmentBatchLimit() { return (Integer) values.get("assignmentBatchLimit"); }

    String rpcTaskId(String group) {
        if (!groups().contains(group)) throw new IllegalArgumentException("Unknown deployment Group");
        try {
            String coordinate = values.get("consumerId") + "\0" + project() + "\0" + group;
            return "perf-rpc-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(coordinate.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    Map<String, String> callerHeaders() throws Exception {
        Path file = Path.of((String) values.get("accessKeyFile"));
        if (Files.size(file) > 4_096) throw new IllegalArgumentException("Invalid deployment access-key file");
        String key = Files.readString(file).strip();
        if (!key.matches("xak_[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("Invalid deployment access-key file");
        return Map.of("Authorization", "Bearer " + key);
    }

    private static int integerOrDefault(Map<String, Object> input, String field, int fallback, int minimum, int maximum) {
        return input.containsKey(field) ? ExperimentConfig.integer(input, field, minimum, maximum) : fallback;
    }

    private static String text(Map<String, Object> input, String field) {
        if (!(input.get(field) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("Missing deployment " + field);
        return value;
    }

    private static String identity(Map<String, Object> input, String field) {
        String value = text(input, field);
        if (!validIdentity(value)) throw new IllegalArgumentException("Invalid deployment " + field);
        return value;
    }

    private static boolean validIdentity(String value) { return value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"); }

    private static String https(Map<String, Object> input, String field) {
        URI uri;
        try { uri = URI.create(text(input, field)); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("Invalid deployment " + field); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65_535)))
            throw new IllegalArgumentException("Deployment " + field + " must be an HTTPS authority");
        return uri.toString().replaceFirst("/$", "");
    }
}
