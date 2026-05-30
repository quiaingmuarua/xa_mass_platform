package com.xa.mass.engine.rules;

import com.alibaba.qlexpress4.Express4Runner;
import com.alibaba.qlexpress4.InitOptions;
import com.alibaba.qlexpress4.QLOptions;
import com.alibaba.qlexpress4.security.QLSecurityStrategy;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleEvaluator;

import java.util.Collections;
import java.util.Map;

/**
 * Default QLExpress rule evaluator for engine matching.
 */
public final class QLExpressRuleEvaluator implements RuleEvaluator<Map<String, Object>> {

    private static final QLOptions EXECUTE_OPTIONS = QLOptions.builder()
            .cache(true)
            .build();

    private final Express4Runner runner = new Express4Runner(InitOptions.builder()
            .securityStrategy(QLSecurityStrategy.open())
            .build());

    @Override
    public boolean evaluate(RuleDefinition rule, Map<String, Object> context) throws Exception {
        Object result = runner.execute(
                        rule.getContent(),
                        context == null ? Collections.emptyMap() : context,
                        EXECUTE_OPTIONS)
                .getResult();
        return Boolean.TRUE.equals(result);
    }
}
