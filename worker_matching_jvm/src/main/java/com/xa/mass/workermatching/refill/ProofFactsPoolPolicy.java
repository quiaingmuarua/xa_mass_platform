package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.index.ProofFactsIndex;
import java.util.function.LongSupplier;

import java.util.*;
import static com.xa.mass.workermatching.pool.CandidatePool.*;

/** Installed only in explicitly configured proof Groups. */
public final class ProofFactsPoolPolicy extends PartitionedPoolPolicy {
    public ProofFactsPoolPolicy(LongSupplier clock, CandidatePool pool, ProofFactsIndex index) { super(clock,pool,index); }
    @Override Criteria criteria(Map<String,List<String>> query) {
        if(query.isEmpty())return new Criteria("","any",List.of());
        if(query.keySet().equals(Set.of("worker.convergenceSlot")))
            return new Criteria("slot:"+RuleQueries.one(query.get("worker.convergenceSlot")),"any",List.of());
        if(!Set.of("worker.proofPool","worker.proofTarget","platform.proofEnabled").containsAll(query.keySet()))
            throw new IllegalArgumentException("unsupported proof query");
        return new Criteria(optional(query,"worker.proofPool")+"|"+
                optional(query,"worker.proofTarget")+"|"+optional(query,"platform.proofEnabled"),"any",List.of());
    }
    private static String optional(Map<String,List<String>> query,String key) {
        return query.containsKey(key)?RuleQueries.one(query.get(key)):"*";
    }
}
