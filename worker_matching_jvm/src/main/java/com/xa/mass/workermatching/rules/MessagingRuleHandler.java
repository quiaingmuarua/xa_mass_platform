package com.xa.mass.workermatching.rules;

import java.util.*;

public final class MessagingRuleHandler extends PartitionedRuleHandler {
    @Override protected Object normalizeLocalInput(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("country", "phone"));
        if (values.containsKey("country")) values.put("country", RuleInputs.countries(values.get("country")));
        if (values.containsKey("phone")) values.put("phone", RuleInputs.text(values.get("phone")));
        return Collections.unmodifiableMap(values);
    }
    @Override protected Selection select(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("country", "phone"));
        String partition = values.containsKey("phone") ? "phone:" + values.get("phone") : "";
        return selection(new PartitionedZsetIndex.Criteria(partition,
                values.containsKey("country") ? "countries" : "any",
                values.containsKey("country") ? RuleInputs.codes(values.get("country")) : List.of()));
    }
    public MessagingRuleHandler(RedisRuleStorage storage) { super(storage,"messaging"); }
    static RedisRuleStorage.IndexMutation index() {
        return new RedisRuleStorage.IndexMutation("messaging",ZsetProjection.prepare( """
            if w['messaging.enabled'] ~= 'true' then return -1, {} end
            local parts = {}
            if type(w.phone) == 'string' and w.phone ~= '' then parts[1]='phone:'..w.phone end
            return country(w.country), parts
            """));
    }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        String partition=query.containsKey("worker.phone")?"phone:"+RuleQueries.one(query.get("worker.phone")):"";
        return countries(query,Set.of("worker.country","worker.phone"),partition);
    }
}
