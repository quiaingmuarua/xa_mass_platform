package com.xa.mass.workermatching.rules;

import java.util.*;

/** Installed only in explicitly configured proof Groups. */
public final class ProofFactsRuleHandler extends PartitionedRuleHandler {
    public ProofFactsRuleHandler(RedisRuleStorage storage) { super(storage,"proof"); }
    static RedisRuleStorage.IndexMutation index() {
        return new RedisRuleStorage.IndexMutation("proof",ZsetProjection.prepare( """
            local pool=type(w.proofPool)=='string' and w.proofPool or '~'
            local target=w.proofTarget=='yes' and 'yes' or 'no'
            local enabled=p.proofEnabled=='yes' and 'yes' or 'no'
            local slot=type(w.convergenceSlot)=='string' and w.convergenceSlot or '~'
            local parts={}
            for _,a in ipairs({'*',pool}) do
              for _,b in ipairs({'*',target}) do
                for _,c in ipairs({'*',enabled}) do parts[#parts+1]=a..'|'..b..'|'..c end
              end
            end
            parts[#parts+1]='slot:'..slot
            return 0,parts
            """));
    }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        if(query.isEmpty())return new PartitionedZsetIndex.Criteria("","any",List.of());
        if(query.keySet().equals(Set.of("worker.convergenceSlot")))
            return new PartitionedZsetIndex.Criteria("slot:"+RuleQueries.one(query.get("worker.convergenceSlot")),"any",List.of());
        if(!Set.of("worker.proofPool","worker.proofTarget","platform.proofEnabled").containsAll(query.keySet()))
            throw new IllegalArgumentException("unsupported proof query");
        return new PartitionedZsetIndex.Criteria(optional(query,"worker.proofPool")+"|"+
                optional(query,"worker.proofTarget")+"|"+optional(query,"platform.proofEnabled"),"any",List.of());
    }
    private static String optional(Map<String,List<String>> query,String key) {
        return query.containsKey(key)?RuleQueries.one(query.get(key)):"*";
    }
}
