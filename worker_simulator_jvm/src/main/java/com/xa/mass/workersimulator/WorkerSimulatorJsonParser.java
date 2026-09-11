package com.xa.mass.workersimulator;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkerSimulatorJsonParser {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "runtimeApiBaseUrl", "sandboxRoot", "controlPort", "workerGroups", "startupPlan");
    private static final Set<String> GROUP_FIELDS = Set.of(
            "events", "count", "propertiesTemplate", "newEnvironment", "requestTimeoutMillis", "reconnectPolicy");
    private static final Set<String> RECONNECT_FIELDS = Set.of(
            "maxUnstableAttempts", "reconnectIntervalMillis", "stableConnectionDurationMillis");

    private WorkerSimulatorJsonParser() {}

    static WorkerSimulatorConfig parse(String encoded, Path configurationDirectory) {
        rejectDuplicateKeys(encoded);
        Map<String, Object> root = Jsons.parseObject(encoded);
        requireFields(root, ROOT_FIELDS, "config");
        String baseUrl = string(root.getOrDefault("runtimeApiBaseUrl", "http://127.0.0.1:18082"), "runtimeApiBaseUrl");
        String sandbox = string(root.getOrDefault("sandboxRoot", "./data/scenario-workers"), "sandboxRoot");
        int port = integer(root.getOrDefault("controlPort", 18086), "controlPort", 0, 65535);
        return new WorkerSimulatorConfig(
                URI.create(baseUrl), configurationDirectory.resolve(sandbox), port,
                parseGroups(object(root.get("workerGroups"), "workerGroups")),
                root.containsKey("startupPlan")
                        ? WorkerSimulatorStartupPlan.parse(object(root.get("startupPlan"), "startupPlan"))
                        : WorkerSimulatorStartupPlan.defaults());
    }

    static List<WorkerSimulatorGroupConfig> parseGroups(Map<String, Object> groups) {
        List<WorkerSimulatorGroupConfig> configs = new ArrayList<>();
        groups.forEach((id, raw) -> {
            Map<String, Object> group = object(raw, "workerGroups." + id);
            requireFields(group, GROUP_FIELDS, "workerGroups." + id);
            List<String> events = new ArrayList<>();
            if (group.containsKey("events")) {
                if (!(group.get("events") instanceof List<?> values)) {
                    throw new IllegalArgumentException("events must be an array");
                }
                for (Object event : values) events.add(string(event, "event"));
            }
            Object reset = group.getOrDefault("newEnvironment", false);
            if (!(reset instanceof Boolean)) throw new IllegalArgumentException("newEnvironment must be boolean");
            configs.add(new WorkerSimulatorGroupConfig(id, events,
                    integer(group.getOrDefault("count", WorkerSimulatorGroupConfig.defaultCount(id)),
                            "count", 0, WorkerSimulatorLab.MAX_WORKERS_PER_GROUP),
                    group.containsKey("propertiesTemplate")
                            ? object(group.get("propertiesTemplate"), "propertiesTemplate")
                            : WorkerSimulatorGroupConfig.defaultPropertiesTemplate(id, events),
                    (Boolean) reset,
                    Duration.ofMillis(positive(group.getOrDefault("requestTimeoutMillis", 10000L), "requestTimeoutMillis")),
                    reconnectPolicy(group)));
        });
        return List.copyOf(configs);
    }

    private static TextMessageReconnectPolicy reconnectPolicy(Map<String, Object> group) {
        TextMessageReconnectPolicy defaults = TextMessageReconnectPolicy.defaults();
        if (!group.containsKey("reconnectPolicy")) return defaults;
        Map<String, Object> policy = object(group.get("reconnectPolicy"), "reconnectPolicy");
        requireFields(policy, RECONNECT_FIELDS, "reconnectPolicy");
        return TextMessageReconnectPolicy.of(
                integer(policy.getOrDefault("maxUnstableAttempts", defaults.maxUnstableAttempts()),
                        "maxUnstableAttempts", 1, Integer.MAX_VALUE),
                Duration.ofMillis(positive(policy.getOrDefault("reconnectIntervalMillis", defaults.reconnectInterval().toMillis()),
                        "reconnectIntervalMillis")),
                Duration.ofMillis(positive(policy.getOrDefault("stableConnectionDurationMillis", defaults.stableConnectionDuration().toMillis()),
                        "stableConnectionDurationMillis")));
    }

    private static long positive(Object value, String name) {
        long number = WorkerSimulatorGroupConfig.integer(value, name);
        if (number <= 0) throw new IllegalArgumentException(name + " must be positive");
        return number;
    }

    private static int integer(Object value, String name, int min, int max) {
        long number = WorkerSimulatorGroupConfig.integer(value, name);
        if (number < min || number > max) throw new IllegalArgumentException(name + " outside " + min + ".." + max);
        return (int) number;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(name + " must be an object");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static void requireFields(Map<String, Object> object, Set<String> allowed, String name) {
        if (!allowed.containsAll(object.keySet())) throw new IllegalArgumentException(name + " contains unknown fields");
    }

    // Shared protocol parsing deliberately does not enforce configuration-key uniqueness.
    private static void rejectDuplicateKeys(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("config must be present");
        try (JsonReader reader = new JsonReader(new StringReader(encoded))) {
            reader.setStrictness(Strictness.STRICT);
            checkKeys(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("config contains trailing content");
        } catch (IOException error) {
            throw new IllegalArgumentException("config is malformed", error);
        }
    }

    private static void checkKeys(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                if (!names.add(reader.nextName())) throw new IllegalArgumentException("config contains duplicate keys");
                checkKeys(reader);
            }
            reader.endObject();
        } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) checkKeys(reader);
            reader.endArray();
        } else {
            reader.skipValue();
        }
    }
}
