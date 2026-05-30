package com.xa.mass.storage.jdbc;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.storage.api.RuleStorage;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class JdbcRuleStorage extends JdbcStorageSupport implements RuleStorage {

    private final JdbcDialect dialect;

    public JdbcRuleStorage(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = dialect;
    }

    @Override
    public synchronized void addRule(RuleDefinition rule) {
        if (rule == null || rule.getId() == null) {
            throw new IllegalArgumentException("rule and rule id are required");
        }
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.ruleUpsertSql())) {
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
    public synchronized void clear() {
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM xa_rule");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear rules", e);
        }
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

