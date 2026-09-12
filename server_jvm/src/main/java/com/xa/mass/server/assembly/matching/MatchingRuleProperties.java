package com.xa.mass.server.assembly.matching;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit Group-local projections. Default identity semantics require no index configuration. */
@ConfigurationProperties(prefix = "xa.mass.worker-matching.rules", ignoreUnknownFields = false)
public record MatchingRuleProperties(Map<String, Set<String>> workerGroups) {
    public MatchingRuleProperties {
        var copy=new LinkedHashMap<String,Set<String>>();
        if (workerGroups!=null) workerGroups.forEach((group,rules) -> {
            if (group==null || group.isBlank()) throw new IllegalArgumentException("Rule WorkerGroup must be non-blank");
            copy.put(group,Set.copyOf(rules));
        });
        workerGroups=Map.copyOf(copy);
    }
}
