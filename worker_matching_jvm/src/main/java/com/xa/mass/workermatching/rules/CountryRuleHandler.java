package com.xa.mass.workermatching.rules;

import java.util.*;

public final class CountryRuleHandler extends PartitionedRuleHandler {
    public CountryRuleHandler(RedisRuleStorage storage) { super(storage,"country"); }
    static RedisRuleStorage.IndexMutation index() {
        return new RedisRuleStorage.IndexMutation("country",ZsetProjection.prepare("return country(w['country']), {}"));
    }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        return countries(query,Set.of("worker.country"),"");
    }
}
