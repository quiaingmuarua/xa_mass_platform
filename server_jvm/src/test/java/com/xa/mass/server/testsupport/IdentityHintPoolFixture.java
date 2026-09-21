package com.xa.mass.server.testsupport;

import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.PoolRefillPolicy;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;


import java.util.Set;
import java.util.function.LongSupplier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime Boundary fixture only: real Pool supply, explicit identity-only execution hints. */
public final class IdentityHintPoolFixture implements PoolRefillPolicy {
    @Override public TargetBatching targetBatching() { return TargetBatching.PAGED; }
    public static final String ID = "proof.identity-hint";
    private final AnyPoolPolicy pool;
    private final com.xa.mass.workermatching.QueryFunction consumer;
    private final AtomicInteger hintsReturned = new AtomicInteger();

    private final CandidatePool stock;

    public IdentityHintPoolFixture(LongSupplier clock, CandidatePool stock) {
        this.stock = stock;
        pool = new AnyPoolPolicy(stock);
        consumer = new AnyQueryFunction(stock);
    }

    public CandidatePool stock() { return stock; }

    public int hintsReturned() { return hintsReturned.get(); }

    @Override public EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        return pool.normalizeQuery(group, query);
    }

    @Override public Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        return pool.deficits(group, targets);
    }

    @Override public List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted) {
        return pool.refill(group, targets, offered, maxAccepted);
    }


    public com.xa.mass.workermatching.QueryFunction queryFunction() {
        return new com.xa.mass.workermatching.QueryFunction() {
            public Object normalizeInput(String group, Object input) {
                return consumer.normalizeInput(group, input);
            }
            public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                var result=new LinkedHashMap<String,WorkerCandidate>();
                consumer.apply(group,inputs).forEach((id,candidate)->result.put(id,new WorkerCandidate(candidate.workerId(),0)));
                hintsReturned.addAndGet(result.size());
                return Collections.unmodifiableMap(result);
            }
        };
    }
}
