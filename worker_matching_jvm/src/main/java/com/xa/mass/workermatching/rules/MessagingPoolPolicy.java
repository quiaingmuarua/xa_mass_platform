package com.xa.mass.workermatching.rules;

import java.util.*;
import com.xa.mass.workermatching.rules.CandidatePool.Selection;
import static com.xa.mass.workermatching.rules.CandidatePool.*;

public final class MessagingPoolPolicy extends PartitionedPoolPolicy {
    public MessagingPoolPolicy(MatchingStorage storage, CandidatePool pool) { super(storage,pool,"messaging"); }
    public static MatchingStorage.IndexMutation index() {
        return new MatchingStorage.IndexMutation("messaging",ZsetProjection.prepare( """
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
