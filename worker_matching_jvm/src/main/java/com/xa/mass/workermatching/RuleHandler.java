package com.xa.mass.workermatching;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import java.util.*;

/** Fixed, explicitly enabled pairs of facts projection and query interpretation. No Task lifecycle. */
enum RuleHandler {
    COUNTRY("worker.country", "country", """
            return country(w['country']), {}
            """) {
        @Override RuleIndex.Criteria criteria(Map<String,List<String>> query) {
            return countries(query, Set.of("worker.country"), "");
        }
    },
    MESSAGING("worker.messaging.available", "messaging", """
            if w['messaging.enabled'] ~= 'true' then return -1, {} end
            local parts = {}
            if type(w.phone) == 'string' and w.phone ~= '' then parts[1]='phone:'..w.phone end
            return country(w.country), parts
            """) {
        @Override RuleIndex.Criteria criteria(Map<String,List<String>> query) {
            String partition = "";
            if (query.containsKey("worker.phone")) {
                List<String> phones = query.get("worker.phone");
                if (phones.size() != 1) throw new IllegalArgumentException("phone requires one value");
                partition = "phone:" + phones.getFirst();
            }
            return countries(query, Set.of("worker.country", "worker.phone"), partition);
        }
    },
    // Only explicitly enabled proof Groups install this projection. It is absent from normal profiles.
    PROOF("proof.worker.facts", "proof", """
            local pool=type(w.proofPool)=='string' and w.proofPool or '~'
            local target=w.proofTarget=='yes' and 'yes' or 'no'
            local enabled=p.proofEnabled=='yes' and 'yes' or 'no'
            local slot=type(w.convergenceSlot)=='string' and w.convergenceSlot or '~'
            local parts={}
            for _,a in ipairs({'*',pool}) do
              for _,b in ipairs({'*',target}) do
                for _,c in ipairs({'*',enabled}) do
                  parts[#parts+1]=a..'|'..b..'|'..c
                end
              end
            end
            parts[#parts+1]='slot:'..slot
            return 0, parts
            """) {
        @Override RuleIndex.Criteria criteria(Map<String,List<String>> query) {
            if (query.isEmpty() || query.containsKey("workerId")) return identities(query);
            var expression=query;
            if (expression.keySet().equals(Set.of("worker.convergenceSlot"))) {
                return new RuleIndex.Criteria("slot:"+one(expression.get("worker.convergenceSlot")), "any", List.of());
            }
            if (!Set.of("worker.proofPool", "worker.proofTarget", "platform.proofEnabled").containsAll(expression.keySet())) {
                throw new IllegalArgumentException("unsupported proof query");
            }
            String partition=optional(expression,"worker.proofPool")+"|"+optional(expression,"worker.proofTarget")+"|"+optional(expression,"platform.proofEnabled");
            return new RuleIndex.Criteria(partition,"any",List.of());
        }
    };

    final String id;
    final String indexName;
    final String projection;
    RuleHandler(String id, String indexName, String projection) {
        this.id=id; this.indexName=indexName; this.projection=projection;
    }
    abstract RuleIndex.Criteria criteria(Map<String,List<String>> query);

    static RuleHandler named(String id) {
        for (var handler : values()) if (handler.id.equals(id)) return handler;
        throw new IllegalArgumentException("unknown Rule");
    }

    static RuleIndex.Criteria identities(Map<String,List<String>> query) {
        return query.isEmpty() ? new RuleIndex.Criteria("", "any", List.of())
                : new RuleIndex.Criteria("", "ids", query.get("workerId"));
    }

    private static RuleIndex.Criteria countries(Map<String,List<String>> query, Set<String> supported, String partition) {
        if (query.isEmpty() || query.containsKey("workerId")) return identities(query);
        if (!supported.containsAll(query.keySet())) throw new IllegalArgumentException("unsupported Rule condition");
        if (!query.containsKey("worker.country")) return new RuleIndex.Criteria(partition,"any",List.of());
        List<String> codes=query.get("worker.country").stream()
                .map(value -> Integer.toString(CountryIndex.code(value))).distinct().toList();
        return new RuleIndex.Criteria(partition,"countries",codes);
    }

    private static String optional(Map<String,List<String>> expression, String name) {
        return expression.containsKey(name) ? one(expression.get(name)) : "*";
    }
    private static String one(List<String> expression) {
        var values=expression;
        if (values.size()!=1) throw new IllegalArgumentException("condition requires one value");
        return values.getFirst();
    }
    static EligibilityQuery normalize(TaskItemWorkerSelector selector, int count) {
        var query = new LinkedHashMap<String,List<String>>();
        selector.expression().forEach((key, value) -> query.put(key,
                key.equals("workerId") ? selector.targetWorkerIds() : conditionValues(value)));
        return new EligibilityQuery(query, count);
    }

    private static List<String> conditionValues(Object expression) {
        if (!(expression instanceof Map<?,?> condition) || !condition.keySet().equals(Set.of("op","values"))
                || !(condition.get("op") instanceof String op) || !(condition.get("values") instanceof List<?> values)
                || values.isEmpty() || values.size()>100 || !(op.equals("in") || op.equals("eq") && values.size()==1)
                || values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
            throw new IllegalArgumentException("condition requires eq with one value or in with 1..100 strings");
        }
        return values.stream().map(String.class::cast).distinct().toList();
    }
}
