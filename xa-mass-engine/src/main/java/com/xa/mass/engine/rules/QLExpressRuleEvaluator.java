package com.xa.mass.engine.rules;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;

import java.util.Map;

public class QLExpressRuleEvaluator implements RuleEvaluator<Map<String, Object>> {
    private final ExpressRunner runner = new ExpressRunner();

    @Override
    public boolean evaluate(RuleDefinition rule, Map<String, Object> context) throws Exception {
        String expr = rule.getContent();
        DefaultContext<String, Object> qlContext = new DefaultContext<>();
        if (context != null) {
            qlContext.putAll(context);
        }
        Object result = runner.execute(expr, qlContext, null, true, false);
        return Boolean.TRUE.equals(result);
    }
}
