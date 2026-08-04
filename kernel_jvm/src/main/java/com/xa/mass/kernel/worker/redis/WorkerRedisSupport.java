package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class WorkerRedisSupport {

    private static final String COMPARE_AND_SET_HASH_FIELD_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current or current ~= ARGV[2] then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """;

    private WorkerRedisSupport() {
    }

    static boolean validIndexField(String field) {
        return field != null
                && field.startsWith("index.")
                && field.length() > "index.".length();
    }

    static String groupsKey(String prefix) {
        return "wr:" + prefix + ":groups";
    }

    static String workersKey(String prefix, String workerGroupId) {
        return "wr:" + prefix + ":workers:" + workerGroupId;
    }

    static String workerIdOwnersKey(String prefix) {
        return "wr:" + prefix + ":worker-id-owners";
    }

    static boolean compareAndSetHashField(
            RedisCommands<String, String> commands,
            String key,
            String field,
            String observed,
            String replacement
    ) {
        Number result = commands.eval(
                COMPARE_AND_SET_HASH_FIELD_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{key},
                field,
                observed,
                replacement
        );
        return result != null && result.longValue() == 1;
    }

    static String propertyValuesKey(
            String prefix,
            String workerGroupId,
            String propertyField
    ) {
        return "wr:" + prefix + ":property-index:"
                + workerGroupId + ":" + propertyField + ":values";
    }

    static String encodeIndexedPropertyValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "indexed property value must be present"
            );
        }
        return encodeCanonical(Map.of("value", value));
    }

    static Object decodeIndexedPropertyValue(String raw) {
        Map<String, Object> payload = Jsons.parseObject(raw);
        requireExactFields(payload, Set.of("value"));
        Object value = payload.get("value");
        if (value == null) {
            throw new IllegalArgumentException(
                    "indexed property value must be present"
            );
        }
        return value;
    }

    static String encodeWorkerGroup(WorkerGroupDescriptor descriptor) {
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("workerGroupId", descriptor.workerGroupId());
            payload.put("attributes", descriptor.attributes());
            payload.put("eventCodes", sortedList(descriptor.eventCodes()));
            return encodeCanonical(payload);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static String encodeWorker(WorkerDescriptor descriptor) {
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("workerId", descriptor.workerId());
            payload.put("workerGroupId", descriptor.workerGroupId());
            payload.put(
                    "endpointManagerId",
                    descriptor.endpointManagerId()
            );
            payload.put("workerProperties", descriptor.workerProperties());
            payload.put(
                    "platformProperties",
                    descriptor.platformProperties()
            );
            return encodeCanonical(payload);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static WorkerGroupDescriptor decodeWorkerGroup(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> payload = Jsons.parseObject(raw);
            requireExactFields(
                    payload,
                    Set.of(
                            "workerGroupId",
                            "attributes",
                            "eventCodes"
                    )
            );
            return new WorkerGroupDescriptor(
                    requireString(payload.get("workerGroupId")),
                    objectMap(payload.get("attributes")),
                    stringSet(payload.get("eventCodes"))
            );
        } catch (IllegalArgumentException | ClassCastException error) {
            return null;
        }
    }

    static WorkerDescriptor decodeWorker(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> payload = Jsons.parseObject(raw);
            requireExactFields(
                    payload,
                    Set.of(
                            "workerId",
                            "workerGroupId",
                            "endpointManagerId",
                            "workerProperties",
                            "platformProperties"
                    )
            );
            return new WorkerDescriptor(
                    requireString(payload.get("workerId")),
                    requireString(payload.get("workerGroupId")),
                    requireString(payload.get("endpointManagerId")),
                    objectMap(payload.get("workerProperties")),
                    objectMap(payload.get("platformProperties"))
            );
        } catch (IllegalArgumentException | ClassCastException error) {
            return null;
        }
    }

    private static void requireExactFields(
            Map<String, Object> payload,
            Set<String> expectedFields
    ) {
        if (!payload.keySet().equals(expectedFields)) {
            throw new IllegalArgumentException(
                    "JSON object fields do not match the Worker Redis ABI"
            );
        }
    }

    private static List<String> sortedList(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String encodeCanonical(Map<String, Object> payload) {
        return escapeNonAscii(Jsons.toJson(canonicalJsonValue(payload)));
    }

    private static String escapeNonAscii(String json) {
        StringBuilder escaped = new StringBuilder(json.length());
        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            if (value <= 0x7f) {
                escaped.append(value);
                continue;
            }
            escaped.append("\\u");
            escaped.append(Character.forDigit((value >>> 12) & 0xf, 16));
            escaped.append(Character.forDigit((value >>> 8) & 0xf, 16));
            escaped.append(Character.forDigit((value >>> 4) & 0xf, 16));
            escaped.append(Character.forDigit(value & 0xf, 16));
        }
        return escaped.toString();
    }

    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            Map<String, Object> sorted = new TreeMap<>();
            mapping.forEach((key, item) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                sorted.put(stringKey, canonicalJsonValue(item));
            });
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> items = new ArrayList<>(collection.size());
            collection.forEach(item -> items.add(canonicalJsonValue(item)));
            return items;
        }
        return value;
    }

    private static String requireString(Object value) {
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalArgumentException(
                    "value must be a non-empty string"
            );
        }
        return text;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("value must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException(
                        "JSON object keys must be strings"
                );
            }
            result.put(stringKey, item);
        });
        return result;
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> sequence)) {
            throw new IllegalArgumentException("value must be an array");
        }
        Set<String> result = new LinkedHashSet<>();
        sequence.forEach(item -> result.add(requireString(item)));
        return result;
    }
}
