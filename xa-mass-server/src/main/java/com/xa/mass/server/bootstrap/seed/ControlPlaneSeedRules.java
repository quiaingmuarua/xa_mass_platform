<<<<<<<< HEAD:xa-mass-server/src/main/java/com/xa/mass/api/sample/SampleRuleBootstrapRequest.java
package com.xa.mass.api.sample;
========
package com.xa.mass.server.bootstrap.seed;
>>>>>>>> origin/main:xa-mass-server/src/main/java/com/xa/mass/server/bootstrap/seed/ControlPlaneSeedRules.java

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
