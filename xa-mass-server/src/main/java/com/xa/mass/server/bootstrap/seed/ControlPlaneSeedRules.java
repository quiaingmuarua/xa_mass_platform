package com.xa.mass.server.bootstrap.seed;

import com.xa.mass.kernel.spi.rule.RuleDefinition;

import java.util.List;

final class ControlPlaneSeedRules {
    private List<RuleDefinition> rules = List.of();

    List<RuleDefinition> getRules() {
        return rules;
    }

    public void setRules(List<RuleDefinition> rules) {
        this.rules = rules != null ? rules : List.of();
    }
}
