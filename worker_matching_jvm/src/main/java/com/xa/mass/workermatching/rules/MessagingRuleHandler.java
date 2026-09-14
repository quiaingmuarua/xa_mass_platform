package com.xa.mass.workermatching.rules;

import java.util.*;

public final class MessagingRuleHandler extends PartitionedRuleHandler {
    public MessagingRuleHandler() { super("messaging", """
            if w['messaging.enabled'] ~= 'true' then return -1, {} end
            local parts = {}
            if type(w.phone) == 'string' and w.phone ~= '' then parts[1]='phone:'..w.phone end
            return country(w.country), parts
            """); }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        String partition=query.containsKey("worker.phone")?"phone:"+RuleQueries.one(query.get("worker.phone")):"";
        return countries(query,Set.of("worker.country","worker.phone"),partition);
    }
}
