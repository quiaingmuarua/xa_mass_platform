package com.xa.mass.workersimulator;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.workersimulator.messaging.MessageScenario;
import com.xa.mass.workersimulator.sms.SmsScenario;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record WorkerSimulatorGroupConfig(
        String workerGroupId,
        List<String> events,
        int count,
        Map<String, Object> propertiesTemplate,
        boolean newEnvironment,
        Duration requestTimeout,
        TextMessageReconnectPolicy reconnectPolicy
) {
    WorkerSimulatorGroupConfig {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) events = defaultEvents(workerGroupId);
        Set<String> unique = new LinkedHashSet<>();
        for (String event : events) {
            requireNonBlank(event, "event");
            if (!unique.add(event)) throw new IllegalArgumentException("events must not contain duplicates");
        }
        events = List.copyOf(events);
        if (count < 0 || count > WorkerSimulatorLab.MAX_WORKERS_PER_GROUP) {
            throw new IllegalArgumentException("count must be between 0 and 15000");
        }
        Map<String, Object> template = new LinkedHashMap<>();
        Objects.requireNonNull(propertiesTemplate, "propertiesTemplate").forEach((key, value) -> {
            requireNonBlank(key, "property key");
            if (Set.of("labInventoryKey", "labInventoryLine", "clientWorkerKey").contains(key)) {
                throw new IllegalArgumentException("propertiesTemplate cannot define inventory identity fields");
            }
            template.put(key, captureValue(value));
        });
        propertiesTemplate = Collections.unmodifiableMap(template);
        if (requestTimeout == null || requestTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
    }

    Map<String, String> generateProperties(long seed, int ordinal) {
        if (ordinal < 1 || ordinal > count) throw new IllegalArgumentException("ordinal outside Group count");
        Map<String, String> properties = new LinkedHashMap<>();
        propertiesTemplate.forEach((key, value) -> properties.put(key, evaluate(seed, key, value, ordinal)));
        return Collections.unmodifiableMap(properties);
    }

    private static Object captureValue(Object value) {
        if (value instanceof String) return value;
        if (!(value instanceof Map<?, ?> expression) || expression.size() != 1) {
            throw new IllegalArgumentException("template value must be a string or one operator object");
        }
        Object name = expression.keySet().iterator().next();
        if (!(name instanceof String operator) || !(expression.get(name) instanceof List<?> params)) {
            throw new IllegalArgumentException("template operator requires a parameter array");
        }
        if (operator.equals("$choice")) {
            if (params.isEmpty() || params.stream().anyMatch(item -> !(item instanceof String))) {
                throw new IllegalArgumentException("$choice requires a nonempty string array");
            }
            return Map.of(operator, List.copyOf(params));
        }
        if (!operator.equals("$index") && !operator.equals("$range")) {
            throw new IllegalArgumentException("unsupported template operator");
        }
        if (params.size() != 2) throw new IllegalArgumentException(operator + " requires two integers");
        long first = integer(params.get(0), operator);
        long second = integer(params.get(1), operator);
        if (operator.equals("$index") && second <= 0) {
            throw new IllegalArgumentException("$index step must be positive");
        }
        if (operator.equals("$range")) {
            if (first > second) throw new IllegalArgumentException("$range min must not exceed max");
            try { Math.addExact(Math.subtractExact(second, first), 1); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("$range width overflows", error); }
        }
        return Map.of(operator, List.of(first, second));
    }

    private String evaluate(long seed, String propertyName, Object value, int ordinal) {
        if (value instanceof String literal) return literal;
        Map<?, ?> expression = (Map<?, ?>) value;
        String operator = (String) expression.keySet().iterator().next();
        List<?> params = (List<?>) expression.get(operator);
        long offset = ordinal - 1L;
        if (operator.equals("$choice")) {
            return (String) params.get((int) Long.remainderUnsigned(hash64(seed, propertyName, offset), params.size()));
        }
        long first = (Long) params.get(0);
        long second = (Long) params.get(1);
        try {
            return Long.toString(operator.equals("$index")
                    ? Math.addExact(first, Math.multiplyExact(offset, second))
                    : Math.addExact(first, Long.remainderUnsigned(hash64(seed, propertyName, offset),
                            Math.addExact(Math.subtractExact(second, first), 1))));
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("template integer result overflows", error);
        }
    }

    private long hash64(long seed, String propertyName, long offset) {
        byte[] group = workerGroupId.getBytes(StandardCharsets.UTF_8);
        byte[] property = propertyName.getBytes(StandardCharsets.UTF_8);
        // Fixed big-endian tuple encoding; neither iteration order nor population size is an input.
        byte[] input = ByteBuffer.allocate(24 + group.length + property.length)
                .putLong(seed).putInt(group.length).put(group)
                .putInt(property.length).put(property).putLong(offset).array();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", error);
        }
    }

    static long integer(Object value, String name) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigDecimal decimal) {
            try { return decimal.longValueExact(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException(name + " must be a 64-bit integer", error); }
        }
        throw new IllegalArgumentException(name + " must be an integer");
    }

    static int defaultCount(String group) {
        return "demo-sim".equals(group) ? 60 : 50;
    }

    static Map<String, Object> defaultPropertiesTemplate(String group, List<String> events) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("runtime", "java");
        if ("demo-sim".equals(group)) {
            template.put("phone", Map.of("$index", List.of(861700000001L, 1)));
            template.put("country", Map.of("$choice", List.of("CN", "US", "GB")));
            template.put("operator", "Preview SIM");
            template.put("simulated", "true");
            if (events.isEmpty() || events.contains(MessageScenario.SEND_EVENT)) {
                template.put("messaging.enabled", "true");
            }
        } else {
            String capability = switch (group) {
                case "scenario-phone-number-workers" -> "libphonenumber";
                case "scenario-string-utils-workers" -> "string-utils";
                default -> throw new IllegalArgumentException(
                        "WorkerGroup has no default properties; configure propertiesTemplate explicitly");
            };
            template.put("capability", capability);
            template.put("region", "local");
            template.put("labSlot", Map.of("$index", List.of(1, 1)));
            template.put("convergenceSlot", "A");
        }
        return Collections.unmodifiableMap(template);
    }

    private static List<String> defaultEvents(String group) {
        if (group.equals("scenario-phone-number-workers")) {
            return PhoneNumberWorkerEvents.definitions().stream().map(WorkerEventDefinition::eventName).toList();
        }
        List<String> events = new ArrayList<>(StringUtilityWorkerEvents.definitions().stream()
                .map(WorkerEventDefinition::eventName).toList());
        if (group.equals("scenario-string-utils-workers")) {
            events.add(WorkerSimulatorLabEvents.CHECKPOINT_EVENT_CODE);
        } else if (group.equals("demo-sim")) {
            events.addAll(List.of(SmsScenario.START_EVENT, SmsScenario.CANCEL_EVENT, MessageScenario.SEND_EVENT));
        } else {
            throw new IllegalArgumentException("WorkerGroup has no default events; configure events explicitly");
        }
        return List.copyOf(events);
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be non-blank");
    }
}
