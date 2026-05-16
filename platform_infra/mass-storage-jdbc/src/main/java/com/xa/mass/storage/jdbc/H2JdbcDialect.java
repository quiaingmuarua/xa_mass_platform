package com.xa.mass.storage.jdbc;

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
    public String ruleUpsertSql() {
        return """
                MERGE INTO xa_rule KEY(rule_id)
                VALUES (?, ?, ?)
                """;
    }

    @Override
    public String principalUpsertSql() {
        return """
                MERGE INTO xa_principal KEY(principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }
}

