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
            assertFalse(combined.contains("supportsproject"),
                    "default rules should not replace WorkerGroup project capability truth: "
                            + id + " -> " + content);
            assertFalse(combined.contains("supportsevent"),
                    "default rules should not replace WorkerGroup event capability truth: "
                            + id + " -> " + content);
            assertFalse(combined.contains("isworkeravailable"),
                    "rule should not read runtime availability evidence: " + id + " -> " + content);
            assertFalse(combined.contains("isworkerlocked"),
                    "rule should not read runtime lock evidence: " + id + " -> " + content);
            assertFalse(combined.contains("isworkerschedulingresourceallocatable"),
                    "rule should not read runtime admission evidence: " + id + " -> " + content);
            assertFalse(combined.contains("workerestimatedloadratio"),
                    "rule should not read runtime load evidence: " + id + " -> " + content);
            assertFalse(combined.contains("estimatedloadratio"),
                    "rule should not read runtime load evidence: " + id + " -> " + content);
            assertFalse(combined.contains("workeractiveleasecount"),
                    "rule should not read runtime lease evidence: " + id + " -> " + content);
            assertFalse(combined.contains("workerreservedcount"),
                    "rule should not read runtime reserve evidence: " + id + " -> " + content);
            assertFalse(combined.contains("lastusedtime"),
                    "rule should not read runtime usage history evidence: " + id + " -> " + content);
            assertFalse(combined.contains("appcount"),
                    "rule should not read aggregate worker support counts as eligibility policy: "
                            + id + " -> " + content);
            assertFalse(combined.contains("agentversion"),
                    "rule should not read agent version as default eligibility policy: "
                            + id + " -> " + content);
        }
    }
}
