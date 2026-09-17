package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;

/** Explicit unconditional stock: no Facts, identity targeting or property views. */
public final class AnyPoolPolicy extends PoolMaintenance<Void> {
    public AnyPoolPolicy(MatchingStorage storage, CandidatePool pool) { super(storage, pool); }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (!query.query().isEmpty()) throw new IllegalArgumentException("any Pool requires an empty target");
        return query;
    }
    @Override protected CandidatePool.Selection target(String group, EligibilityQuery query) { return CandidatePool.all(); }
    @Override protected Map<String, Void> readQualifications(String group, List<String> ids) { return Map.of(); }
    @Override protected Map<String, String> memberships(String group, String id, Void ignored) { return Map.of(); }
}
