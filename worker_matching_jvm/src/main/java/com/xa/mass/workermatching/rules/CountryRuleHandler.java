package com.xa.mass.workermatching.rules;

import java.util.*;

public final class CountryRuleHandler extends PartitionedRuleHandler {
    @Override protected Object normalizeLocalInput(String group, Object input) {
        if (input instanceof Map<?, ?> map && map.isEmpty()) return Map.of();
        return RuleInputs.countries(input);
    }
    @Override protected Selection select(String group, Object input) {
        return input instanceof Map<?, ?> ? all() : range("country", RuleInputs.codes(input));
    }
    public CountryRuleHandler(RedisRuleStorage storage) { super(storage,"country"); }
    static RedisRuleStorage.IndexMutation index() {
        return new RedisRuleStorage.IndexMutation("country",ZsetProjection.prepare("return country(w['country']), {}"));
    }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        return countries(query,Set.of("worker.country"),"");
    }
}
