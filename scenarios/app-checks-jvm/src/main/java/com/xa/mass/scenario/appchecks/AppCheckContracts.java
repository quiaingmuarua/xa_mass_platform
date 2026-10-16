package com.xa.mass.scenario.appchecks;

import java.util.*;
import com.xa.mass.workerdelivery.json.Jsons;

/** Immutable startup protocol lookup; application/event names do not select implementation branches. */
public final class AppCheckContracts {
    public static final String REGISTRATION = "registration-v1";
    public static final String SIMULATED = "simulated-registration-v1";
    public static final String ORIGINAL_CARRIER = "original-carrier-v1";
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
                .map(contract -> Map.<String, Object>of("id", contract.id(), "label", contract.label(), "seedType", contract.seedType().id(), "description", contract.description(),
                        "parameterExample", contract.parameterExample(), "inputExample", contract.inputExample(),
                        "resultExample", contract.resultExample())).toList();
    }
    public static AppCheckContract registration() { return new Registration(); }
    public static AppCheckContract simulatedRegistration() { return new SimulatedRegistration(); }
    public static AppCheckContract originalCarrier() { return new OriginalCarrier(); }

    private static class Registration implements AppCheckContract {
        public String id() { return REGISTRATION; }
        public String label() { return "注册检查"; }
        public SeedType seedType() { return SeedType.PHONENUMBER; }
        public String description() { return "号码查询协议；Worker 返回注册状态，不要求模拟字段"; }
        public Map<String, Object> parameterExample() { return Map.of(); }
        public Map<String, Object> inputExample() { return Map.of("number", "+8613800000001"); }
        public Map<String, Object> resultExample() {
            return Map.of("number", "+8613800000001", "registered", true,
                    "workerId", "executing-worker", "workerGroupId", "configured-group");
        }
        public List<String> exportHeaders() { return List.of("注册状态"); }
        public List<String> exportValues(Result result) { return List.of(Boolean.TRUE.equals(result.details().get("registered")) ? "已注册" : "未注册"); }
        public Map<String, Object> normalizeParameters(Object value) {
            AppCheckSettings.object(value, Set.of(), Set.of(), "parameters");
            return Map.of();
        }
        public Map<String, Object> payload(Context context, String number) { return Map.of("number", number); }
        public String inputSeed(Map<String, Object> payload) {
            String number = AppCheckSpecification.text(payload.get("number"), 16);
            if (!number.matches("\\+[1-9][0-9]{1,14}")) throw new IllegalArgumentException("Invalid input number");
            return number;
        }
        public Result result(Context context, Map<String, Object> payload, Map<String, Object> content) {
            String number = inputSeed(payload);
            if (!number.equals(content.get("number")) || !context.workerGroupId().equals(content.get("workerGroupId"))
                    || !(content.get("registered") instanceof Boolean registered))
                throw new IllegalArgumentException("Result association mismatch");
            return new Result(number, registered, Map.of("registered", registered, "workerId", AppCheckSpecification.text(content.get("workerId"), 256),
                    "workerGroupId", context.workerGroupId()));
        }
    }
    private static final class SimulatedRegistration extends Registration {
        public String id() { return SIMULATED; }
        public String label() { return "模拟注册"; }
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
            long delay = AppCheckSpecification.integer(content.get("simulatedDelayMillis"));
            if (delay < 0 || delay > 30_000) throw new IllegalArgumentException("Invalid simulated delay");
            var details = new LinkedHashMap<>(answer.details());
            details.put("simulatedDelayMillis", delay);
            return new Result(answer.seed(), answer.matched(), details);
        }
    }

    private static final class OriginalCarrier implements AppCheckContract {
        private static final Set<String> NUMBER_ERRORS = Set.of("INVALID_PHONE_NUMBER", "PARSE_INVALID_COUNTRY_CODE",
                "PARSE_NOT_A_NUMBER", "PARSE_TOO_SHORT_AFTER_IDD", "PARSE_TOO_SHORT_NSN", "PARSE_TOO_LONG");

        public String id() { return ORIGINAL_CARRIER; }
        public String label() { return "原始运营商识别"; }
        public SeedType seedType() { return SeedType.PHONENUMBER; }
        public String description() { return "判断号码能否识别到原始运营商；基于号码库元数据，不表示当前运营商或号码在用状态"; }
        public Map<String, Object> parameterExample() { return Map.of(); }
        public Map<String, Object> inputExample() { return Map.of("rawNumber", "+8613800000000"); }
        public Map<String, Object> resultExample() {
            return Map.of("input", "+8613800000000", "possible", true, "valid", true, "originalCarrier", "China Mobile");
        }
        public Map<String, Object> normalizeParameters(Object value) {
            AppCheckSettings.object(value, Set.of(), Set.of(), "parameters");
            return Map.of();
        }
        public Map<String, Object> payload(Context context, String number) { return Map.of("rawNumber", number); }
        public String inputSeed(Map<String, Object> payload) {
            String number = AppCheckSpecification.text(payload.get("rawNumber"), 16);
            if (!number.matches("\\+[1-9][0-9]{1,14}")) throw new IllegalArgumentException("Invalid input number");
            return number;
        }
        public Result result(Context context, Map<String, Object> payload, Map<String, Object> content) {
            String number = inputSeed(payload);
            if (!number.equals(content.get("input")) || !(content.get("possible") instanceof Boolean possible)
                    || !(content.get("valid") instanceof Boolean valid))
                throw new IllegalArgumentException("Carrier result association or shape mismatch");
            if (valid) {
                if (!possible || !(content.get("originalCarrier") instanceof String carrier) || content.containsKey("error"))
                    throw new IllegalArgumentException("Invalid carrier answer");
                return new Result(number, !carrier.isBlank(), Map.of("originalCarrier", carrier,
                        "reason", carrier.isBlank() ? "NO_CARRIER_MAPPING" : "IDENTIFIED"));
            }
            if (!(content.get("error") instanceof String reason) || !NUMBER_ERRORS.contains(reason)
                    || content.containsKey("originalCarrier"))
                throw new IllegalArgumentException("Invalid negative carrier answer");
            return new Result(number, false, Map.of("originalCarrier", "", "reason", reason));
        }
        public List<String> exportHeaders() { return List.of("原始运营商", "识别说明"); }
        public List<String> exportValues(Result result) {
            return List.of((String) result.details().get("originalCarrier"), (String) result.details().get("reason"));
        }
    }
}
