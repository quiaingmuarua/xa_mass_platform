package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.*;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.function.*;

/** Group identity eligibility, retaining the existing explicitly enabled country query capability. */
public final class DefaultRuleHandler implements RuleHandler {
    @Override public Bound bind(Supplier<RedisCommands<String,String>> commands,String base,Set<String> enabled) {
        var countryHandler=new CountryRuleHandler();
        var country=enabled.contains("worker.country")?countryHandler.bind(commands,base,enabled):null;
        return new Bound() {
            @Override public EligibilityQuery normalize(Map<String,?> expression,int count) {
                if(expression.containsKey("workerId") && expression.size()!=1)
                    throw new IllegalArgumentException("workerId cannot be combined with property conditions");
                var query=RuleQueries.normalize(expression,count);
                if(!identity(query)) {
                    if(country==null)throw new IllegalArgumentException("country index unavailable");
                    return country.normalize(expression,count);
                }
                return query;
            }
            @Override public EligibilityQuery selector(Map<String,?> expression,int count) {
                if(!expression.isEmpty() && !expression.containsKey("workerId"))RuleQueries.requireConditions(expression);
                return normalize(expression,count);
            }
            @Override public Query compile(EligibilityQuery query) {
                if(query.query().isEmpty())return member -> true;
                if(query.query().containsKey("workerId")) {
                    var ids=Set.copyOf(query.query().get("workerId"));
                    return new Query() {
                        public boolean matches(Member member) { return ids.contains(member.workerId()); }
                        public int target(int requested) { return Math.min(requested,ids.size()); }
                    };
                }
                if(country==null)throw new IllegalArgumentException("country index unavailable");
                return country.compile(query);
            }
            @Override public Map<String,Member> snapshot(List<String> ids) {
                return country==null?Map.of():country.snapshot(ids);
            }
        };
    }
    private static boolean identity(EligibilityQuery query) {
        return query.query().isEmpty() || query.query().keySet().equals(Set.of("workerId"));
    }
}
