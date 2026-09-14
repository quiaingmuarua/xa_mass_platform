package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import java.util.*;

/** Local query and admission mechanics over a Rule's projection of Pacer-issued identities. */
final class SharedEligibility {
    private final SharedEligibilityInventory inventory;
    private final SharedEligibilityInventory.Scope scope;
    private final RuleHandler.Bound handler;

    SharedEligibility(SharedEligibilityInventory inventory,SharedEligibilityInventory.Scope scope,
                       RuleHandler.Bound handler) {
        this.inventory=inventory; this.scope=scope; this.handler=handler;
    }

    private void requireIdentityRule(Map<String,?> expression) {
        if(expression.containsKey("workerId") && !scope.ruleId().equals(WorkerMatchingCatalog.DEFAULT_RULE_ID))
            throw new IllegalArgumentException("workerId query requires worker.default");
    }
    EligibilityQuery normalize(Map<String,?> expression,int count) {
        requireIdentityRule(expression);
        var normalized=handler.normalize(expression,count);
        requireIdentityRule(normalized.query());
        return normalized;
    }
    EligibilityQuery selector(Map<String,?> expression,int count) {
        requireIdentityRule(expression);
        var normalized=handler.selector(expression,count);
        requireIdentityRule(normalized.query());
        return normalized;
    }

    private Map<EligibilityQuery,RuleHandler.Query> prepare(List<EligibilityQuery> queries) {
        if(queries.size()>100)throw new IllegalArgumentException("at most 100 queries");
        var result=new LinkedHashMap<EligibilityQuery,RuleHandler.Query>();
        queries.forEach(q->result.put(q,handler.compile(normalize(q.query(),q.count()))));
        return result;
    }

    private Map<EligibilityQuery,Integer> missing(Map<EligibilityQuery,RuleHandler.Query> queries) {
        var stock=inventory.snapshot(scope);
        var result=new LinkedHashMap<EligibilityQuery,Integer>();
        queries.forEach((query,compiled)->result.put(query,Math.max(0,compiled.target(query.count())
                -(int)stock.stream().filter(entry->compiled.matches(entry.member())).count())));
        return result;
    }

    Map<EligibilityQuery,Integer> deficits(List<EligibilityQuery> targets) {
        return missing(prepare(targets));
    }

    int room() { return inventory.room(scope); }

    /** Invocation-local admission plan; the Catalog renews all selected scopes in one Group call. */
    List<SharedEligibilityInventory.Entry> select(List<EligibilityQuery> targets,
            List<HeldCandidate> offered, int budget) {
        var queries=prepare(targets);
        var missing=missing(queries);
        if(missing.values().stream().noneMatch(count->count>0))return List.of();
        budget=Math.min(budget,inventory.room(scope));
        if(budget<=0 || offered.isEmpty())return List.of();
        var ids=offered.stream().map(HeldCandidate::workerId).toList();
        var projections=handler.snapshot(ids);
        if(!new HashSet<>(ids).containsAll(projections.keySet()))throw new IllegalStateException("Rule returned an unoffered identity");
        var selected=new LinkedHashMap<String,RuleHandler.Member>();
        var accepted=new ArrayList<SharedEligibilityInventory.Entry>();
        for(boolean any:List.of(false,true)) {
            var active=new LinkedHashMap<EligibilityQuery,RuleHandler.Query>();
            queries.forEach((q,compiled)->{ if(q.query().isEmpty()==any)active.put(q,compiled); });
            for(var candidate:offered) {
                if(accepted.size()==budget)break;
                var member=projections.getOrDefault(candidate.workerId(),new RuleHandler.Member(candidate.workerId(),null));
                if(!candidate.workerId().equals(member.workerId()))throw new IllegalStateException("Rule projection identity mismatch");
                if(needed(member,active,missing,selected))accepted.add(new SharedEligibilityInventory.Entry(candidate,member.projection()));
            }
        }
        return List.copyOf(accepted);
    }

    private static boolean needed(RuleHandler.Member member,Map<EligibilityQuery,RuleHandler.Query> queries,
            Map<EligibilityQuery,Integer> missing,Map<String,RuleHandler.Member> selected) {
        if(selected.containsKey(member.workerId()))return false;
        for(var entry:queries.entrySet()) {
            var query=entry.getValue();
            if(query.matches(member) && selected.values().stream().filter(query::matches).count()<missing.get(entry.getKey())) {
                selected.put(member.workerId(),member); return true;
            }
        }
        return false;
    }

    Map<EligibilityQuery,List<HeldCandidate>> take(List<EligibilityQuery> requests) {
        if(requests.stream().mapToInt(EligibilityQuery::count).sum()>100)
            throw new IllegalArgumentException("at most 100 candidates per take");
        var queries=prepare(requests);
        var stock=inventory.snapshot(scope);
        var selected=new LinkedHashMap<EligibilityQuery,List<SharedEligibilityInventory.Entry>>();
        var seen=new HashSet<String>();
        queries.forEach((query,compiled)->{
            var entries=new ArrayList<SharedEligibilityInventory.Entry>();
            for(var entry:stock) {
                if(entries.size()==query.count())break;
                if(!seen.contains(entry.held().workerId()) && compiled.matches(entry.member())) {
                    seen.add(entry.held().workerId()); entries.add(entry);
                }
            }
            selected.put(query,entries);
        });
        return inventory.take(scope,selected);
    }
}
