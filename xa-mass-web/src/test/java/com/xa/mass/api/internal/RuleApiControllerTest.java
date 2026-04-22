package com.xa.mass.api.internal;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleApiControllerTest {

    private RuleManager<Object> ruleManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ruleManager = new RuleManager<>(TaskStorageFactory.createDefaultRuleStorage());
        mockMvc = MockMvcBuilders.standaloneSetup(new RuleApiController(ruleManager)).build();
    }

    @Test
    void listRulesReturnsCoreFields() throws Exception {
        RuleDefinition rule = new RuleDefinition();
        rule.setId("rule-001");
        rule.setName("Prefer online workers");
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent("worker.status == 'ONLINE'");
        rule.setDescription("Mainline worker match rule");
        rule.setEnabled(true);
        rule.setPriority(10);
        ruleManager.addDefaultRule(rule);

        mockMvc.perform(get("/status/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].ruleId").value("rule-001"))
                .andExpect(jsonPath("$.data.items[0].type").value("QL_EXPRESS"))
                .andExpect(jsonPath("$.data.items[0].content").value("worker.status == 'ONLINE'"));
    }

    @Test
    void ruleMetaReturnsRuleTypesAndRegisteredTypes() throws Exception {
        mockMvc.perform(get("/status/api/rules/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleTypes[0]").exists());
    }
}
