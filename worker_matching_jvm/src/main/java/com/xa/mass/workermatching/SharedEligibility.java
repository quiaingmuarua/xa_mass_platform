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

    RuleHandler.Query compile(EligibilityQuery query) {
        return handler.compile(normalize(query.query(),query.count()));
    }

    Map<EligibilityQuery,RuleHandler.Query> compile(List<EligibilityQuery> queries) {
        if(queries.size()>100)throw new IllegalArgumentException("at most 100 queries");
        var result=new LinkedHashMap<EligibilityQuery,RuleHandler.Query>();
        queries.forEach(q->result.put(q,compile(q)));
        return result;
    }

    Map<EligibilityQuery,Integer> deficits(Map<EligibilityQuery,RuleHandler.Query> queries) {
        var stock=inventory.snapshot(scope);
        var result=new LinkedHashMap<EligibilityQuery,Integer>();
        queries.forEach((query,compiled)->result.put(query,Math.max(0,compiled.target(query.count())
                -(int)stock.stream().filter(entry->compiled.matches(entry.member())).count())));
        return result;
    }

    int room() { return inventory.room(scope); }

    /** Plans admission of Pacer-held identities; no stock is committed during Handler work. */
    List<RuleHandler.Member> select(Map<EligibilityQuery,RuleHandler.Query> queries,
            List<String> offered, int budget) {
        var missing=deficits(queries);
        if(missing.values().stream().noneMatch(count->count>0))return List.of();
        budget=Math.min(budget,inventory.room(scope));
        if(budget<=0 || offered.isEmpty())return List.of();
        var projections=handler.snapshot(offered);
        if(!new HashSet<>(offered).containsAll(projections.keySet()))throw new IllegalStateException("Rule returned an unoffered identity");
        var ordered=List.copyOf(queries.entrySet());
        var selected=new HashSet<String>();
        var selectedCounts=new int[ordered.size()];
        var matches=new HashMap<String,boolean[]>();
        var accepted=new ArrayList<RuleHandler.Member>();
        for(boolean any:List.of(false,true)) {
            int outstanding=0;
            for(int i=0;i<ordered.size();i++) {
                var target=ordered.get(i).getKey();
                if(target.query().isEmpty()==any && selectedCounts[i]<missing.get(target))outstanding++;
            }
            if(outstanding==0)continue;
            for(var candidate:offered) {
                if(accepted.size()==budget || outstanding==0)break;
                if(selected.contains(candidate))continue;
                var member=projections.getOrDefault(candidate,new RuleHandler.Member(candidate,null));
                if(!candidate.equals(member.workerId()))throw new IllegalStateException("Rule projection identity mismatch");
                var matching=matches.computeIfAbsent(member.workerId(),ignored->{
                    var result=new boolean[ordered.size()];
                    for(int i=0;i<ordered.size();i++)result[i]=ordered.get(i).getValue().matches(member);
                    return result;
                });
                for(int i=0;i<ordered.size();i++) {
                    var target=ordered.get(i).getKey();
                    if(target.query().isEmpty()==any && matching[i] && selectedCounts[i]<missing.get(target)) {
                        selected.add(member.workerId());
                        accepted.add(member);
                        for(int j=0;j<matching.length;j++)if(matching[j]) {
                            selectedCounts[j]++;
                            var matchedTarget=ordered.get(j).getKey();
                            if(matchedTarget.query().isEmpty()==any && selectedCounts[j]==missing.get(matchedTarget))outstanding--;
                        }
                        break;
                    }
                }
            }
        }
        return List.copyOf(accepted);
    }

    Map<EligibilityQuery,List<HeldCandidate>> take(List<EligibilityQuery> requests) {
        if(requests.stream().mapToInt(EligibilityQuery::count).sum()>100)
            throw new IllegalArgumentException("at most 100 candidates per take");
        var queries=compile(requests);
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
