package com.xa.mass.server.storage;

final class H2JdbcDialect implements JdbcDialect {

    @Override
    public String taskUpsertSql() {
        return """
                MERGE INTO xa_task KEY(task_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
    }

    @Override
    public String workerUpsertSql() {
        return """
                MERGE INTO xa_worker KEY(worker_id)
                VALUES (?, ?, ?)
                """;
    }

    @Override
    public String workerContextUpsertSql() {
        return """
                MERGE INTO xa_worker_context KEY(worker_context_id)
                VALUES (?, ?, ?)
                """;
    }

    @Override
    public String ruleUpsertSql() {
        return """
                MERGE INTO xa_rule KEY(rule_id)
                VALUES (?, ?, ?)
                """;
    }
}
