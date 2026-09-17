package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.RuleHandler;
import com.xa.mass.workermatching.rules.DefaultRuleHandler;
import com.xa.mass.workermatching.rules.RedisRuleStorage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime Boundary fixture only: real Pool supply, explicit identity-only execution hints. */
public final class IdentityHintRuleHandler implements RuleHandler {
    public static final String ID = "proof.identity-hint";
    private final RuleHandler pool;
    private final AtomicInteger hintsReturned = new AtomicInteger();

    public IdentityHintRuleHandler(RedisRuleStorage storage) {
        pool = new DefaultRuleHandler(storage, Map.of());
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

    @Override public Map<EligibilityQuery, List<WorkerCandidate>> take(String group, Map<EligibilityQuery, Integer> limits) {
        var result = new LinkedHashMap<EligibilityQuery, List<WorkerCandidate>>();
        pool.take(group, limits).forEach((query, candidates) -> {
            result.put(query, candidates.stream().map(candidate -> new WorkerCandidate(candidate.workerId(), 0)).toList());
            hintsReturned.addAndGet(candidates.size());
        });
        return Collections.unmodifiableMap(result);
    }
}
