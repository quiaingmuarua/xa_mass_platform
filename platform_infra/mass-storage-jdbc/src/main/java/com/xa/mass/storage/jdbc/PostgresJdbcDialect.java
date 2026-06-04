package com.xa.mass.storage.jdbc;

final class PostgresJdbcDialect implements JdbcDialect {

    @Override
    public String taskUpsertSql() {
        return """
                INSERT INTO xa_task(task_id, status, project, schedulable, create_time, max_runtime_deadline, json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  project = EXCLUDED.project,
                  schedulable = EXCLUDED.schedulable,
                  create_time = EXCLUDED.create_time,
                  max_runtime_deadline = EXCLUDED.max_runtime_deadline,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String ruleUpsertSql() {
        return """
                INSERT INTO xa_rule(rule_id, rule_type, json)
                VALUES (?, ?, ?)
                ON CONFLICT (rule_id) DO UPDATE SET
                  rule_type = EXCLUDED.rule_type,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String principalUpsertSql() {
        return """
                INSERT INTO xa_principal(principal_id, principal_type, credential_hash, key_prefix, user_id, project_scope, enabled, json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (principal_id) DO UPDATE SET
                  principal_type = EXCLUDED.principal_type,
                  credential_hash = EXCLUDED.credential_hash,
                  key_prefix = EXCLUDED.key_prefix,
                  user_id = EXCLUDED.user_id,
                  project_scope = EXCLUDED.project_scope,
                  enabled = EXCLUDED.enabled,
                  json = EXCLUDED.json
                """;
    }
}
