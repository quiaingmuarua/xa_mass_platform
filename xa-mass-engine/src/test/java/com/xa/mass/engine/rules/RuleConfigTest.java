package com.xa.mass.engine.rules;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleConfigTest {

    @Test
    void defaultRulesUseWorkerSchedulingSurface() {
        List<RuleDefinition> rules = RuleConfig.getDefaultWorkerMatchRules();

        assertTrue(rules.stream().anyMatch(rule -> "worker_scheduling_resource_check".equals(rule.getId())));
        assertNoWorkerContextRuleSurface(rules);
    }

    @Test
    void derivedRuleSetsKeepDefaultRulesOnWorkerSchedulingSurface() {
        assertNoWorkerContextRuleSurface(RuleConfig.getLooseWorkerMatchRules());
        assertNoWorkerContextRuleSurface(RuleConfig.getAdvancedWorkerMatchRules());
        assertNoWorkerContextRuleSurface(RuleConfig.getProjectSpecificRules("demoApp"));
    }

    private void assertNoWorkerContextRuleSurface(List<RuleDefinition> rules) {
        for (RuleDefinition rule : rules) {
            String id = rule.getId() == null ? "" : rule.getId();
            String content = rule.getContent() == null ? "" : rule.getContent();
            String combined = (id + " " + content).toLowerCase();
            assertFalse(combined.contains("workercontext"),
                    "rule should not use legacy workerContext surface: " + id + " -> " + content);
            assertFalse(combined.contains("worker_context"),
                    "rule should not use legacy worker_context surface: " + id + " -> " + content);
        }
    }
}
