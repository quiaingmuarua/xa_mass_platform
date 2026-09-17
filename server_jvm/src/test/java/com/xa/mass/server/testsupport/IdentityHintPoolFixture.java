package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.PoolRefillPolicy;
import com.xa.mass.workermatching.rules.*;
import java.util.Set;
import com.xa.mass.workermatching.rules.MatchingStorage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime Boundary fixture only: real Pool supply, explicit identity-only execution hints. */
public final class IdentityHintPoolFixture implements PoolRefillPolicy {
    public static final String ID = "proof.identity-hint";
    private final DefaultPoolPolicy pool;
    private final com.xa.mass.workermatching.QueryFunctions consumer;
    private final AtomicInteger hintsReturned = new AtomicInteger();

    public IdentityHintPoolFixture(MatchingStorage storage) {
        var stock = new CandidatePool(storage);
        pool = new DefaultPoolPolicy(storage, stock, Set.of());
        consumer = PoolQueryFunctions.defaults(stock, Set.of());
    }

    public int hintsReturned() { return hintsReturned.get(); }

    @Override public EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        return pool.normalizeQuery(group, query);
    }

    @Override public Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        return pool.deficits(group, targets);
    }

    @Override public List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            List<HeldCandidate> offered, int maxAccepted) {
        return pool.refill(group, targets, offered, maxAccepted);
    }

    public com.xa.mass.workermatching.QueryFunctions queryFunctions() {
        return new com.xa.mass.workermatching.QueryFunctions(consumer.normalizeInput(),(group,inputs)-> {
            var result=new LinkedHashMap<String,WorkerCandidate>();
            consumer.execute().apply(group,inputs).forEach((id,candidate)->result.put(id,new WorkerCandidate(candidate.workerId(),0)));
            hintsReturned.addAndGet(result.size());
            return Collections.unmodifiableMap(result);
        });
    }
}
