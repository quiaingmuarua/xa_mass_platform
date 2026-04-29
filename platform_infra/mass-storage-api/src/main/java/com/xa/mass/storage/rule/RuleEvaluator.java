package com.xa.mass.storage.rule;

public interface RuleEvaluator<T> {
    boolean evaluate(RuleDefinition rule, T context) throws Exception;
}
