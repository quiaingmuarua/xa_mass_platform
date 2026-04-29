package com.xa.mass.engine.rules;

import com.xa.mass.engine.storage.InMemoryRuleStorage;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleEvaluator;
import com.xa.mass.storage.rule.RuleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RuleManagerTest {

    private RuleManager<Map<String, Object>> manager;

    @BeforeEach
    void setUp() {
        manager = new RuleManager<>(new InMemoryRuleStorage());
    }

    // ---- CRUD ----

    @Test
    void addAndRetrieveRule() {
        RuleDefinition rule = rule("r1", RuleType.QL_EXPRESS, "true");
        manager.addDefaultRule(rule);

        Optional<RuleDefinition> found = manager.getRule("r1");
        assertTrue(found.isPresent());
        assertEquals("r1", found.get().getId());
    }

    @Test
    void getRuleReturnsEmptyWhenNotFound() {
        assertTrue(manager.getRule("nonexistent").isEmpty());
    }

    @Test
    void addDefaultRulesInBatch() {
        manager.addDefaultRules(List.of(
                rule("r2", RuleType.QL_EXPRESS, "true"),
                rule("r3", RuleType.QL_EXPRESS, "false")
        ));
        assertEquals(2, manager.getDefaultRules().size());
    }

    @Test
    void removeDefaultRuleDeletesIt() {
        manager.addDefaultRule(rule("r4", RuleType.QL_EXPRESS, "true"));
        manager.removeDefaultRule("r4");
        assertTrue(manager.getRule("r4").isEmpty());
    }

    @Test
    void updateRuleReturnsTrue() {
        RuleDefinition r = rule("r5", RuleType.QL_EXPRESS, "true");
        manager.addDefaultRule(r);
        r.setContent("false");
        assertTrue(manager.updateRule(r));
    }

    @Test
    void deleteRuleReturnsTrue() {
        manager.addDefaultRule(rule("r6", RuleType.QL_EXPRESS, "true"));
        assertTrue(manager.deleteRule("r6"));
        assertTrue(manager.getRule("r6").isEmpty());
    }

    @Test
    void getRulesByTypeFilters() {
        manager.addDefaultRule(rule("r7", RuleType.QL_EXPRESS, "true"));
        manager.addDefaultRule(rule("r8", RuleType.JSON_DSL, "{}"));
        List<RuleDefinition> qlRules = manager.getRulesByType(RuleType.QL_EXPRESS);
        assertEquals(1, qlRules.size());
        assertEquals("r7", qlRules.get(0).getId());
    }

    // ---- evaluate ----

    @Test
    void evaluateTrueExpressionReturnsTrue() throws Exception {
        RuleDefinition r = rule("eval-true", RuleType.QL_EXPRESS, "1 == 1");
        assertTrue(manager.evaluate(r, Map.of()));
    }

    @Test
    void evaluateFalseExpressionReturnsFalse() throws Exception {
        RuleDefinition r = rule("eval-false", RuleType.QL_EXPRESS, "1 == 2");
        assertFalse(manager.evaluate(r, Map.of()));
    }

    @Test
    void evaluateUnsupportedTypeThrows() {
        RuleDefinition r = rule("bad-type", RuleType.JSON_DSL, "{}");
        // InMemoryRuleStorage only registers QL_EXPRESS evaluator by default
        assertThrows(IllegalArgumentException.class, () -> manager.evaluate(r, Map.of()));
    }

    @Test
    void evaluateDefaultRulesReturnsHitIds() {
        manager.addDefaultRule(rule("hit1", RuleType.QL_EXPRESS, "1 == 1"));
        manager.addDefaultRule(rule("miss1", RuleType.QL_EXPRESS, "1 == 2"));
        manager.addDefaultRule(rule("hit2", RuleType.QL_EXPRESS, "2 > 1"));

        List<String> hits = manager.evaluateDefaultRules(Map.of());
        assertEquals(List.of("hit1", "hit2"), hits);
    }

    // ---- evaluator registry ----

    @Test
    void registerAndRetrieveEvaluator() {
        RuleEvaluator evaluator = (rule, ctx) -> true;
        manager.registerEvaluator(RuleType.JSON_DSL, evaluator);
        assertTrue(manager.getEvaluator(RuleType.JSON_DSL).isPresent());
    }

    @Test
    void removeEvaluatorReturnsTrue() {
        // QL_EXPRESS is registered by default
        assertTrue(manager.removeEvaluator(RuleType.QL_EXPRESS));
        assertTrue(manager.getEvaluator(RuleType.QL_EXPRESS).isEmpty());
    }

    @Test
    void clearRemovesAllRulesAndRestoresDefaultEvaluators() {
        manager.addDefaultRule(rule("cx1", RuleType.QL_EXPRESS, "true"));
        manager.clear();
        // rules removed
        assertTrue(manager.getDefaultRules().isEmpty());
        // InMemoryRuleStorage re-registers QL_EXPRESS after clear as factory default
        assertTrue(manager.getEvaluator(RuleType.QL_EXPRESS).isPresent());
    }

    // ---- helpers ----

    private RuleDefinition rule(String id, RuleType type, String content) {
        RuleDefinition r = new RuleDefinition();
        r.setId(id);
        r.setType(type);
        r.setContent(content);
        return r;
    }
}
