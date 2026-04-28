package com.xa.mass.server.storage;

import com.xa.mass.engine.rules.QLExpressRuleEvaluator;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleEvaluator;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.engine.storage.RuleStorage;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class H2RuleStorage extends JdbcStorageSupport implements RuleStorage {

    private final Map<RuleType, RuleEvaluator> evaluatorMap = new ConcurrentHashMap<>();

    public H2RuleStorage(String jdbcUrl, String username, String password) {
        super(jdbcUrl, username, password);
        initialize();
        registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
    }

    private void initialize() {
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS xa_rule (
                      rule_id VARCHAR(128) PRIMARY KEY,
                      rule_type VARCHAR(64),
                      json CLOB NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_xa_rule_type ON xa_rule(rule_type)");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize H2 rule storage", e);
        }
    }

    @Override
    public synchronized void addRule(RuleDefinition rule) {
        if (rule == null || rule.getId() == null) {
            throw new IllegalArgumentException("rule and rule id are required");
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                MERGE INTO xa_rule KEY(rule_id)
                VALUES (?, ?, ?)
                """)) {
            ps.setString(1, rule.getId());
            ps.setString(2, rule.getType() == null ? null : rule.getType().name());
            ps.setString(3, json(rule));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save rule " + rule.getId(), e);
        }
    }

    @Override
    public Optional<RuleDefinition> getRule(String ruleId) {
        return queryRules("SELECT json FROM xa_rule WHERE rule_id = ?", ruleId).stream().findFirst();
    }

    @Override
    public synchronized boolean updateRule(RuleDefinition rule) {
        if (rule == null || rule.getId() == null) {
            return false;
        }
        try (var conn = connection(); var ps = conn.prepareStatement("""
                UPDATE xa_rule SET rule_type = ?, json = ? WHERE rule_id = ?
                """)) {
            ps.setString(1, rule.getType() == null ? null : rule.getType().name());
            ps.setString(2, json(rule));
            ps.setString(3, rule.getId());
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update rule " + rule.getId(), e);
        }
    }

    @Override
    public synchronized boolean deleteRule(String ruleId) {
        try (var conn = connection(); var ps = conn.prepareStatement("DELETE FROM xa_rule WHERE rule_id = ?")) {
            ps.setString(1, ruleId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete rule " + ruleId, e);
        }
    }

    @Override
    public List<RuleDefinition> getAllRules() {
        return queryRules("SELECT json FROM xa_rule");
    }

    @Override
    public List<RuleDefinition> getRulesByType(RuleType ruleType) {
        return queryRules("SELECT json FROM xa_rule WHERE rule_type = ?", ruleType == null ? null : ruleType.name());
    }

    @Override
    public void addRules(Collection<RuleDefinition> rules) {
        if (rules == null) {
            return;
        }
        for (RuleDefinition rule : rules) {
            addRule(rule);
        }
    }

    @Override
    public void deleteRules(Collection<String> ruleIds) {
        if (ruleIds == null) {
            return;
        }
        for (String ruleId : ruleIds) {
            deleteRule(ruleId);
        }
    }

    @Override
    public void registerEvaluator(RuleType ruleType, RuleEvaluator evaluator) {
        evaluatorMap.put(ruleType, evaluator);
    }

    @Override
    public Optional<RuleEvaluator> getEvaluator(RuleType ruleType) {
        return Optional.ofNullable(evaluatorMap.get(ruleType));
    }

    @Override
    public List<RuleType> getRegisteredEvaluatorTypes() {
        return new ArrayList<>(evaluatorMap.keySet());
    }

    @Override
    public boolean removeEvaluator(RuleType ruleType) {
        return evaluatorMap.remove(ruleType) != null;
    }

    @Override
    public synchronized void clear() {
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM xa_rule");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear rules", e);
        }
        evaluatorMap.clear();
        registerEvaluator(RuleType.QL_EXPRESS, new QLExpressRuleEvaluator());
    }

    private List<RuleDefinition> queryRules(String sql, String... args) {
        try (var conn = connection(); var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<RuleDefinition> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readJson(rs.getString(1), RuleDefinition.class));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query rules", e);
        }
    }
}
