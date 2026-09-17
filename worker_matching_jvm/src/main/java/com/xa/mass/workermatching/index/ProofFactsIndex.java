package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.function.Supplier;

/** Facts-derived qualification resource; independent of held candidate inventory. */
public final class ProofFactsIndex extends PartitionedZsetIndex {
    public ProofFactsIndex(Supplier<RedisCommands<String, String>> commands, RedisKeyspace keyspace) {
        super(commands, keyspace, "proof");
    }
    public static IndexMutation mutation() {
        return new IndexMutation("proof",ZsetProjection.prepare( """
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
}
