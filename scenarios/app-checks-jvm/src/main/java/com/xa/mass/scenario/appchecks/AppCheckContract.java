package com.xa.mass.scenario.appchecks;

import java.util.List;
import java.util.Map;

/** A statically installed, pure input/result protocol. No resources or execution lifecycle. */
public interface AppCheckContract {
    String id();
    String label();
    SeedType seedType();
    String description();
    Map<String, Object> parameterExample();
    Map<String, Object> inputExample();
    Map<String, Object> resultExample();
    Map<String, Object> normalizeParameters(Object value);
    /** Validates one normalized, nonempty seed before any import batch is written. */
    default void validateSeed(String seed) {}
    Map<String, Object> payload(Context context, String seed);
    String inputSeed(Map<String, Object> payload);
    Result result(Context context, Map<String, Object> payload, Map<String, Object> content);
    default List<String> exportHeaders() { return List.of(); }
    default List<String> exportValues(Result result) { return List.of(); }

    record Context(String checkId, String country, String workerGroupId, String salt,
                   Map<String, Object> parameters) {
        public Context { parameters = AppCheckSettings.parameters(parameters, "task.parameters"); }
    }
    record Result(String seed, boolean matched, Map<String, Object> details) {
        public Result {
            if (seed == null || seed.isEmpty()) throw new IllegalArgumentException("Missing result seed");
            details = Map.copyOf(details);
        }
    }

    enum SeedType {
        PHONENUMBER("phonenumber"), TEXT("text");
        private final String id;
        SeedType(String id) { this.id = id; }
        public String id() { return id; }
        public void requireCountry(String country) {
            if (this == PHONENUMBER ? !AppCheckSpecification.PREFIXES.containsKey(country == null ? "" : country) : country != null)
                throw new IllegalArgumentException(this == PHONENUMBER ? "号码种子需要有效国家" : "text 种子不接受 country");
        }
        public String normalize(String raw, String country) {
            if (this == TEXT) return raw.replaceAll("^[ \\t]+|[ \\t]+$", "");
            String value = raw.strip();
            if (value.isEmpty()) return value;
            if (!value.startsWith("+")) value = "+" + value;
            String prefix = AppCheckSpecification.PREFIXES.get(country);
            if (prefix == null || !value.matches("\\+[1-9][0-9]{1,14}")
                    || !value.startsWith(prefix) || value.length() <= prefix.length())
                throw new IllegalArgumentException("号码格式或国家前缀无效");
            return value;
        }
        public String messageId(String seed) {
            return this == PHONENUMBER
                    ? "number-" + AppCheckSpecification.digest("app-checks/v2/number", seed)
                    : "seed-" + AppCheckSpecification.digest("app-checks/v1/text", seed);
        }
    }
}
