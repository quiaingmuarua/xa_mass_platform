package com.xa.mass.storage.memory;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRuleStorageTest {

    @Test
    void ruleStorageStoresRulesAsDefinitionStoreOnly() {
        InMemoryRuleStorage storage = new InMemoryRuleStorage();
        RuleDefinition rule = testRule();

        storage.addRule(rule);

        assertThat(storage.getRule(rule.getId())).isPresent();
        assertThat(storage.getRulesByType(rule.getType())).hasSize(1);
        assertThat(storage.deleteRule(rule.getId())).isTrue();
        assertThat(storage.getAllRules()).isEmpty();
    }

    private static RuleDefinition testRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("basic_worker_check");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("isWorkerAvailable == true && isWorkerLocked == false");
        rule.setDescription("Worker must be available and unlocked");
        return rule;
    }
}
