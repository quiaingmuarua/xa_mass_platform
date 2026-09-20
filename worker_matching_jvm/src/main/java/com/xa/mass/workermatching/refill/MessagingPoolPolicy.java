package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.index.MessagingIndex;

import java.util.*;
import static com.xa.mass.workermatching.pool.CandidatePool.*;

public final class MessagingPoolPolicy extends PartitionedPoolPolicy {
    public MessagingPoolPolicy(CandidatePool pool, MessagingIndex index) { super(pool,index); }
    @Override Criteria criteria(Map<String,List<String>> query) {
        String partition=query.containsKey("worker.phone")?"phone:"+RuleQueries.one(query.get("worker.phone")):"";
        return countries(query,Set.of("worker.country","worker.phone"),partition);
    }
}
