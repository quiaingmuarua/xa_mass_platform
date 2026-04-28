package com.xa.mass.workerpack.sample.api.model;

import com.xa.mass.engine.rules.RuleDefinition;

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
