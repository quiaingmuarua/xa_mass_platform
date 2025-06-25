package com.xa.mass.engine.rules;

public interface RuleEvaluator<T> {
    boolean evaluate(RuleDefinition rule, T context) throws Exception;
}
