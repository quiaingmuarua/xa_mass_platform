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

    @Override
    public String catalogEventUpsertSql() {
        return """
                INSERT INTO xa_catalog_event(event_code, name, description, payload_types_json, task_modes_json, enabled,
                  default_routing_code, priority_class, response_mode, delivery_acknowledgement_mode, convergence_mode, target_scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(event_code) DO UPDATE SET
                  name = excluded.name,
                  description = excluded.description,
                  payload_types_json = excluded.payload_types_json,
                  task_modes_json = excluded.task_modes_json,
                  enabled = excluded.enabled,
                  default_routing_code = excluded.default_routing_code,
                  priority_class = excluded.priority_class,
                  response_mode = excluded.response_mode,
                  delivery_acknowledgement_mode = excluded.delivery_acknowledgement_mode,
                  convergence_mode = excluded.convergence_mode,
                  target_scope = excluded.target_scope
                """;
    }

    @Override
    public String catalogProjectUpsertSql() {
        return """
                INSERT INTO xa_catalog_project(project_code, tenant_id, name, description, enabled, owner_principal_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_code) DO UPDATE SET
                  tenant_id = excluded.tenant_id,
                  name = excluded.name,
                  description = excluded.description,
                  enabled = excluded.enabled,
                  owner_principal_id = excluded.owner_principal_id
                """;
    }
}
