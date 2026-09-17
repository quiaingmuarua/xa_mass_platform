package com.xa.mass.workermatching.rules;

import java.util.*;

/** Local parameter helpers for the fixed Pool strategies, not a shared query language. */
final class RuleInputs {
    private RuleInputs() { }
    static Map<String, Object> object(Object input, Set<String> fields) {
        if (!(input instanceof Map<?, ?> map) || map.size() > 100 || !fields.containsAll(map.keySet()))
            throw new IllegalArgumentException("unsupported Pool input fields");
        var result = new TreeMap<String, Object>();
        map.forEach((key, value) -> result.put((String) key, value));
        return result;
    }
    static List<String> strings(Object input) {
        if (!(input instanceof List<?> values) || values.isEmpty() || values.size() > 100)
            throw new IllegalArgumentException("requires 1..100 strings");
        var result = new TreeSet<String>();
        for (Object value : values) result.add(text(value));
        return List.copyOf(result);
    }
    static String text(Object input) {
        if (!(input instanceof String text) || text.isBlank()) throw new IllegalArgumentException("requires a non-blank string");
        return text;
    }
    static List<String> countries(Object input) {
        var result = strings(input); result.forEach(CountryIndex::code); return result;
    }
    static List<String> codes(Object input) {
        return countries(input).stream().map(country -> Integer.toString(CountryIndex.code(country))).toList();
    }
}
