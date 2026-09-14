package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.EligibilityQuery;
import java.util.*;

/** The existing Rules share eq/in syntax; other Handlers may define different parameters. */
final class RuleQueries {
    static EligibilityQuery normalize(Map<String,?> expression,int count) {
        var result=new LinkedHashMap<String,List<String>>();
        expression.forEach((key,value) -> {
            Object values=value;
            if (value instanceof Map<?,?> condition) {
                if (!condition.keySet().equals(Set.of("op","values"))
                        || !(condition.get("op") instanceof String op)
                        || !(condition.get("values") instanceof List<?> list)
                        || !(op.equals("in") || op.equals("eq") && list.size()==1)) {
                    throw new IllegalArgumentException("condition requires eq with one value or in with 1..100 strings");
                }
                values=condition.get("values");
            }
            if (!(values instanceof List<?> list) || list.isEmpty() || list.size()>100
                    || list.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank())) {
                throw new IllegalArgumentException("condition requires 1..100 non-blank strings");
            }
            result.put(key,List.copyOf(new TreeSet<>(list.stream().map(String.class::cast).toList())));
        });
        return new EligibilityQuery(result,count);
    }
    static void requireConditions(Map<String,?> expression) {
        if(expression.values().stream().anyMatch(value->!(value instanceof Map<?,?>)))
            throw new IllegalArgumentException("HTTP property conditions require op and values");
    }
    static String one(List<String> values) {
        if(values.size()!=1)throw new IllegalArgumentException("condition requires one value");
        return values.getFirst();
    }
    private RuleQueries() { }
}
