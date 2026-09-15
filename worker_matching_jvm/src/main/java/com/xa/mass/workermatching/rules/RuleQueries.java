package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;

/** Set-valued parameters used by the current Rules; query structure carries no operator semantics. */
final class RuleQueries {
    static EligibilityQuery normalize(EligibilityQuery query) {
        var result = new LinkedHashMap<String, List<String>>();
        query.query().forEach((key, values) -> result.put(key, List.copyOf(new TreeSet<>(values))));
        return new EligibilityQuery(result);
    }
    static String one(List<String> values) {
        if (values.size() != 1) throw new IllegalArgumentException("condition requires one value");
        return values.getFirst();
    }
    private RuleQueries() { }
}
