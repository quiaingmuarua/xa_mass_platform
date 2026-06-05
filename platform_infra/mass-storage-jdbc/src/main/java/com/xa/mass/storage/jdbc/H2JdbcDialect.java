package com.xa.mass.storage.jdbc;

final class H2JdbcDialect implements JdbcDialect {

    @Override
    public String taskUpsertSql() {
        return """
                MERGE INTO xa_task(task_id, status, project, schedulable, create_time, max_runtime_deadline, json)
                KEY(task_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
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

    @Override
    public String catalogEventUpsertSql() {
        return """
                MERGE INTO xa_catalog_event KEY(event_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    @Override
    public String catalogProjectUpsertSql() {
        return """
                MERGE INTO xa_catalog_project KEY(project_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
    }
}
