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

    @Override
    public String catalogEventUpsertSql() {
        return """
                INSERT INTO xa_catalog_event(event_code, name, description, payload_types_json, task_modes_json, enabled,
                  default_routing_code, priority_class, response_mode, delivery_acknowledgement_mode, convergence_mode, target_scope)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_code) DO UPDATE SET
                  name = EXCLUDED.name,
                  description = EXCLUDED.description,
                  payload_types_json = EXCLUDED.payload_types_json,
                  task_modes_json = EXCLUDED.task_modes_json,
                  enabled = EXCLUDED.enabled,
                  default_routing_code = EXCLUDED.default_routing_code,
                  priority_class = EXCLUDED.priority_class,
                  response_mode = EXCLUDED.response_mode,
                  delivery_acknowledgement_mode = EXCLUDED.delivery_acknowledgement_mode,
                  convergence_mode = EXCLUDED.convergence_mode,
                  target_scope = EXCLUDED.target_scope
                """;
    }

    @Override
    public String catalogProjectUpsertSql() {
        return """
                INSERT INTO xa_catalog_project(project_code, tenant_id, name, description, enabled, owner_principal_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_code) DO UPDATE SET
                  tenant_id = EXCLUDED.tenant_id,
                  name = EXCLUDED.name,
                  description = EXCLUDED.description,
                  enabled = EXCLUDED.enabled,
                  owner_principal_id = EXCLUDED.owner_principal_id
                """;
    }
}
