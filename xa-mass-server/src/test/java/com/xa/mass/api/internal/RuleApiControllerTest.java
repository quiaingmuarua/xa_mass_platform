package com.xa.mass.api.internal;

import com.xa.mass.sdk.RuleOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuleApiControllerTest {

    @Mock
    private RuleOperations ruleOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RuleApiController(ruleOperations)).build();
    }

    @Test
    void listRulesReturnsCoreFields() throws Exception {
        when(ruleOperations.listDefaultRules()).thenReturn(List.of(Map.of(
                "ruleId", "rule-001",
                "name", "Prefer online workers",
                "type", "QL_EXPRESS",
                "content", "worker.status == 'ONLINE'",
                "description", "Mainline worker match rule",
                "enabled", true,
                "priority", 10
        )));

        mockMvc.perform(get("/api/v1/runtime/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].ruleId").value("rule-001"))
                .andExpect(jsonPath("$.data.items[0].type").value("QL_EXPRESS"))
                .andExpect(jsonPath("$.data.items[0].content").value("worker.status == 'ONLINE'"));
    }

    @Test
    void ruleMetaReturnsRuleTypesAndRegisteredTypes() throws Exception {
        when(ruleOperations.listRuleTypes()).thenReturn(List.of("QL_EXPRESS", "JSON_DSL"));
        when(ruleOperations.listRegisteredEvaluatorTypes()).thenReturn(List.of("QL_EXPRESS"));

        mockMvc.perform(get("/api/v1/runtime/rules/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ruleTypes[0]").value("QL_EXPRESS"))
                .andExpect(jsonPath("$.data.registeredEvaluatorTypes[0]").value("QL_EXPRESS"));
    }
}
