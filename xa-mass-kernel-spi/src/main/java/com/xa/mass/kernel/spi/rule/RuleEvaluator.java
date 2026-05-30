package com.xa.mass.kernel.spi.rule;

public interface RuleEvaluator<T> {
    boolean evaluate(RuleDefinition rule, T context) throws Exception;
}
