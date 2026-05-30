package com.xa.mass.sdk;

import com.xa.mass.kernel.spi.rule.RuleDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface RuleOperations {

    List<Map<String, Object>> listDefaultRules();

    List<String> listRuleTypes();

    List<String> listRegisteredEvaluatorTypes();

    void replaceDefaultRules(Collection<RuleDefinition> rules);
}
