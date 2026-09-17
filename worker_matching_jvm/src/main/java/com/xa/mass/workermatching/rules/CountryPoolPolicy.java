package com.xa.mass.workermatching.rules;

import java.util.*;
import com.xa.mass.workermatching.rules.CandidatePool.Selection;
import static com.xa.mass.workermatching.rules.CandidatePool.*;

public final class CountryPoolPolicy extends PartitionedPoolPolicy {
    public CountryPoolPolicy(MatchingStorage storage, CandidatePool pool) { super(storage,pool,"country"); }
    public static MatchingStorage.IndexMutation index() {
        return new MatchingStorage.IndexMutation("country",ZsetProjection.prepare("return country(w['country']), {}"));
    }
    @Override PartitionedZsetIndex.Criteria criteria(Map<String,List<String>> query) {
        return countries(query,Set.of("worker.country"),"");
    }
}
