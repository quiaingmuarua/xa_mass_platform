package com.xa.mass.server.storage;

final class PostgresJdbcDialect implements JdbcDialect {

    @Override
    public String taskUpsertSql() {
        return """
                INSERT INTO xa_task(task_id, status, project, schedulable, max_runtime_deadline, json)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  project = EXCLUDED.project,
                  schedulable = EXCLUDED.schedulable,
                  max_runtime_deadline = EXCLUDED.max_runtime_deadline,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String taskMessageUpsertSql() {
        return """
                INSERT INTO xa_task_msg(task_id, message_id, status, final_state, json)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (task_id, message_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  final_state = EXCLUDED.final_state,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String taskMessageAttemptUpsertSql() {
        return """
                INSERT INTO xa_task_msg_attempt(task_id, message_id, attempt_id, status, active_state, json)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (task_id, message_id, attempt_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  active_state = EXCLUDED.active_state,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String workerUpsertSql() {
        return """
                INSERT INTO xa_worker(worker_id, worker_group_id, json)
                VALUES (?, ?, ?)
                ON CONFLICT (worker_id) DO UPDATE SET
                  worker_group_id = EXCLUDED.worker_group_id,
                  json = EXCLUDED.json
                """;
    }

    @Override
    public String workerContextUpsertSql() {
        return """
                INSERT INTO xa_worker_context(worker_context_id, worker_id, json)
                VALUES (?, ?, ?)
                ON CONFLICT (worker_context_id) DO UPDATE SET
                  worker_id = EXCLUDED.worker_id,
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
}
