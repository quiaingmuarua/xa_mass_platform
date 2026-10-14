package com.xa.mass.scenario.appchecks;

import java.util.*;
import com.xa.mass.workerdelivery.json.Jsons;

/** Immutable startup protocol lookup; application/event names do not select implementation branches. */
public final class AppCheckContracts {
    public static final String REGISTRATION = "registration-v1";
    public static final String SIMULATED = "simulated-registration-v1";
    private final Map<String, AppCheckContract> contracts;

    public AppCheckContracts(List<AppCheckContract> definitions) {
        var collected = new LinkedHashMap<String, AppCheckContract>();
        for (var definition : definitions) {
            AppCheckSpecification.text(definition.id(), 128);
            if (collected.putIfAbsent(definition.id(), definition) != null)
                throw new IllegalArgumentException("Duplicate App Checks contract: " + definition.id());
        }
        contracts = Collections.unmodifiableMap(collected);
    }
    public AppCheckContract require(String id) {
        var contract = contracts.get(id);
        if (contract == null) throw new IllegalArgumentException("Unknown App Checks contract: " + id);
        return contract;
    }
    public List<Map<String, Object>> descriptions() {
        return contracts.values().stream().sorted(Comparator.comparing(AppCheckContract::id))
                .map(contract -> Map.<String, Object>of("id", contract.id(), "description", contract.description(),
                        "parameterExample", contract.parameterExample(), "inputExample", contract.inputExample(),
                        "resultExample", contract.resultExample())).toList();
    }
    public static AppCheckContract registration() { return new Registration(); }
    public static AppCheckContract simulatedRegistration() { return new SimulatedRegistration(); }

    private static class Registration implements AppCheckContract {
        public String id() { return REGISTRATION; }
        public String description() { return "号码查询协议；Worker 返回注册状态，不要求模拟字段"; }
        public Map<String, Object> parameterExample() { return Map.of(); }
        public Map<String, Object> inputExample() { return Map.of("number", "+8613800000001"); }
        public Map<String, Object> resultExample() {
            return Map.of("number", "+8613800000001", "registered", true,
                    "workerId", "executing-worker", "workerGroupId", "configured-group");
        }
        public Map<String, Object> normalizeParameters(Object value) {
            AppCheckSettings.object(value, Set.of(), Set.of(), "parameters");
            return Map.of();
        }
        public Map<String, Object> payload(Context context, String number) { return Map.of("number", number); }
        public String inputNumber(Map<String, Object> payload) {
            String number = AppCheckSpecification.text(payload.get("number"), 16);
            if (!number.matches("\\+[1-9][0-9]{1,14}")) throw new IllegalArgumentException("Invalid input number");
            return number;
        }
        public Result result(Context context, Map<String, Object> payload, Map<String, Object> content) {
            String number = inputNumber(payload);
            if (!number.equals(content.get("number")) || !context.workerGroupId().equals(content.get("workerGroupId"))
                    || !(content.get("registered") instanceof Boolean registered))
                throw new IllegalArgumentException("Result association mismatch");
            return new Result(number, registered, AppCheckSpecification.text(content.get("workerId"), 256),
                    context.workerGroupId(), null);
        }
    }
    private static final class SimulatedRegistration extends Registration {
        public String id() { return SIMULATED; }
        public String description() { return "确定性模拟协议；保留实际 Worker、salt、区间和延迟的独立复算"; }
        public Map<String, Object> parameterExample() {
            return Map.of("ranges", Map.of("registered", List.of(0, 500), "unregistered", List.of(500, 900),
                    "failed", List.of(900, 1000)), "delayMs", List.of(2000, 5000));
        }
        public Map<String, Object> inputExample() {
            var input = new LinkedHashMap<String, Object>(parameterExample());
            input.put("number", "+8613800000001"); input.put("salt", "fixed-salt");
            return Collections.unmodifiableMap(input);
        }
        public Map<String, Object> resultExample() {
            var result = new LinkedHashMap<String, Object>(super.resultExample());
            result.put("simulatedDelayMillis", 4067);
            return Collections.unmodifiableMap(result);
        }
        public Map<String, Object> normalizeParameters(Object value) {
            if (!(value instanceof Map<?, ?> description) || !description.keySet().equals(Set.of("ranges", "delayMs")))
                throw new IllegalArgumentException("Expected simulation ranges and delayMs");
            if (Jsons.toJson(description).length() > 4096) throw new IllegalArgumentException("Simulation exceeds 4096 characters");
            if (!(description.get("ranges") instanceof Map<?, ?> ranges)
                    || !ranges.keySet().equals(Set.of("registered", "unregistered", "failed")))
                throw new IllegalArgumentException("Expected registered, unregistered and failed ranges");
            var normalized = new LinkedHashMap<String, Object>();
            boolean[] covered = new boolean[1000];
            for (String key : List.of("registered", "unregistered", "failed")) {
                List<Long> bounds = range(ranges.get(key), 1000);
                for (int bucket = bounds.getFirst().intValue(); bucket < bounds.getLast(); bucket++) {
                    if (covered[bucket]) throw new IllegalArgumentException("Ranges overlap");
                    covered[bucket] = true;
                }
                normalized.put(key, bounds);
            }
            for (boolean present : covered) if (!present) throw new IllegalArgumentException("Ranges have a gap");
            var result = new LinkedHashMap<String, Object>();
            result.put("ranges", Collections.unmodifiableMap(normalized));
            result.put("delayMs", range(description.get("delayMs"), 30_000));
            return Collections.unmodifiableMap(result);
        }

        private static List<Long> range(Object value, long maximum) {
            if (!(value instanceof List<?> values) || values.size() != 2) throw new IllegalArgumentException("Expected two integer bounds");
            long first = AppCheckSpecification.integer(values.get(0)), last = AppCheckSpecification.integer(values.get(1));
            if (first < 0 || first > last || last > maximum) throw new IllegalArgumentException("Invalid range bounds");
            return List.of(first, last);
        }

        public Map<String, Object> payload(Context context, String number) {
            var payload = new LinkedHashMap<String, Object>(context.parameters());
            payload.put("number", number); payload.put("salt", context.salt());
            return Collections.unmodifiableMap(payload);
        }
        public Result result(Context context, Map<String, Object> payload, Map<String, Object> content) {
            var answer = super.result(context, payload, content);
            return new Result(answer.number(), answer.registered(), answer.workerId(), answer.workerGroupId(),
                    AppCheckSpecification.integer(content.get("simulatedDelayMillis")));
        }
    }
}
