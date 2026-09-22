package com.xa.mass.workermatching.buckets;

import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import java.util.*;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** One complete tuple per candidate; partial query interpretation remains with the Proof rule. */
public final class ProofFactsBuckets {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> FIELDS = List.of("proofPool", "proofTarget", "proofEnabled", "convergenceSlot");
    private ProofFactsBuckets() { }

    public static @Nullable String bucketKey(@Nullable WorkerFacts facts) {
        if (facts == null) return null;
        var worker = facts.workerProperties();
        String pool = worker.get("proofPool") instanceof String value ? value : null;
        String target = "yes".equals(worker.get("proofTarget")) ? "yes" : "no";
        String enabled = "yes".equals(facts.platformProperties().get("proofEnabled")) ? "yes" : "no";
        String slot = worker.get("convergenceSlot") instanceof String value ? value : null;
        return JSON.writeValueAsString(new String[] { pool, target, enabled, slot });
    }

    /** Decode each directory key once for the whole caller batch, without retaining a second index. */
    public static Map<String, List<String>> decodeKeys(Collection<String> keys) {
        var result = new LinkedHashMap<String, List<String>>();
        for (String key : keys) {
            var values = JSON.readValue(key, String[].class);
            if (values.length != FIELDS.size()) throw new IllegalArgumentException("invalid Proof bucket key");
            result.put(key, Collections.unmodifiableList(Arrays.asList(values)));
        }
        return Collections.unmodifiableMap(result);
    }

    public static Set<String> matchingKeys(Map<String, ?> query, Map<String, List<String>> directory) {
        var result = new LinkedHashSet<String>();
        for (var entry : directory.entrySet()) {
            boolean matches = true;
            for (var condition : query.entrySet()) {
                int index = FIELDS.indexOf(condition.getKey());
                if (index < 0) throw new IllegalArgumentException("unsupported Proof field");
                if (!Objects.equals(condition.getValue(), entry.getValue().get(index))) { matches = false; break; }
            }
            if (matches) result.add(entry.getKey());
        }
        return Collections.unmodifiableSet(result);
    }
}
