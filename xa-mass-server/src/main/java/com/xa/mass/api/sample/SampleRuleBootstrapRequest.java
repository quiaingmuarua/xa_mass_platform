package com.xa.mass.api.sample;

import com.xa.mass.kernel.spi.rule.RuleDefinition;

import java.util.List;

public class SampleRuleBootstrapRequest {

    private List<RuleDefinition> rules = List.of();

    public List<RuleDefinition> getRules() {
        return rules;
    }

    public void setRules(List<RuleDefinition> rules) {
        this.rules = rules != null ? rules : List.of();
    }
}
