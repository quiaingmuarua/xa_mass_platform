package com.xa.mass.workermatching.views;

import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.*;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** The seven fixed proof-field combinations and the independent convergence slot. */
public final class ProofFactsViews {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ProofFactsViews() { }

    public static @Nullable Map<String, String> memberships(@Nullable WorkerFacts facts) {
        if (facts == null) return null;
        var worker = facts.workerProperties();
        String pool = worker.get("proofPool") instanceof String value ? value : null;
        String target = "yes".equals(worker.get("proofTarget")) ? "yes" : "no";
        String enabled = "yes".equals(facts.platformProperties().get("proofEnabled")) ? "yes" : "no";
        var views = new LinkedHashMap<String, String>();
        for (int fields = 1; fields < 8; fields++) views.put("proof:" + fields, combination(fields, pool, target, enabled));
        String slot = worker.get("convergenceSlot") instanceof String value ? value : null;
        views.put("convergenceSlot", tuple(slot));
        return views;
    }

    public static CandidatePool.Selection select(Map<String, ?> values) {
        if (values.isEmpty()) return CandidatePool.all();
        if (values.containsKey("convergenceSlot"))
            return CandidatePool.range("convergenceSlot", List.of(tuple((String) values.get("convergenceSlot"))));
        int fields = (values.containsKey("proofPool") ? 1 : 0)
                | (values.containsKey("proofTarget") ? 2 : 0) | (values.containsKey("proofEnabled") ? 4 : 0);
        return CandidatePool.range("proof:" + fields, List.of(combination(fields,
                (String) values.get("proofPool"), (String) values.get("proofTarget"), (String) values.get("proofEnabled"))));
    }

    private static String combination(int fields, @Nullable String pool, @Nullable String target, @Nullable String enabled) {
        return tuple((fields & 1) == 0 ? null : pool, (fields & 2) == 0 ? null : target, (fields & 4) == 0 ? null : enabled);
    }

    private static String tuple(@Nullable String... values) {
        return JSON.writeValueAsString(values);
    }
}
