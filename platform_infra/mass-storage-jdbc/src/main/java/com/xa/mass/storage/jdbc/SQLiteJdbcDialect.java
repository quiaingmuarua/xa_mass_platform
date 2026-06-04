package com.xa.mass.storage.jdbc;

final class SQLiteJdbcDialect implements JdbcDialect {

    @Override
    public String taskUpsertSql() {
        return """
                INSERT INTO xa_task(task_id, status, project, schedulable, create_time, max_runtime_deadline, json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                  status = excluded.status,
                  project = excluded.project,
                  schedulable = excluded.schedulable,
                  create_time = excluded.create_time,
                  max_runtime_deadline = excluded.max_runtime_deadline,
                  json = excluded.json
                """;
    }

    @Override
    public String ruleUpsertSql() {
        return """
                INSERT INTO xa_rule(rule_id, rule_type, json)
                VALUES (?, ?, ?)
                ON CONFLICT(rule_id) DO UPDATE SET
                  rule_type = excluded.rule_type,
                  json = excluded.json
                """;
    }

    @Override
    public String principalUpsertSql() {
        return """
                INSERT INTO xa_principal(principal_id, principal_type, credential_hash, key_prefix, user_id, project_scope, enabled, json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(principal_id) DO UPDATE SET
                  principal_type = excluded.principal_type,
                  credential_hash = excluded.credential_hash,
                  key_prefix = excluded.key_prefix,
                  user_id = excluded.user_id,
                  project_scope = excluded.project_scope,
                  enabled = excluded.enabled,
                  json = excluded.json
                """;
    }
}
