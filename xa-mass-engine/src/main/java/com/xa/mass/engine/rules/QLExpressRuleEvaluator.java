package com.xa.mass.engine.rules;

import com.ql.util.express.DefaultContext;
import com.ql.util.express.ExpressRunner;

import java.util.Map;

public class QLExpressRuleEvaluator implements RuleEvaluator<Map<String, Object>> {
    private final ExpressRunner runner = new ExpressRunner();

    @Override
    public boolean evaluate(RuleDefinition rule, Map<String, Object> context) throws Exception {
        // content是QLExpress脚本
        String expr = rule.getContent();
        // QLExpress上下文
        DefaultContext<String, Object> qlContext = new DefaultContext<>();
        if (context != null) {
            qlContext.putAll(context);
        }
        // 执行
        Object result = runner.execute(expr, qlContext, null, true, false);
        // 允许返回null或其他类型，只要返回true即命中
        return Boolean.TRUE.equals(result);
    }
}
