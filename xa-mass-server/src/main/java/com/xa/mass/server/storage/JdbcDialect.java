package com.xa.mass.server.storage;

interface JdbcDialect {

    String taskUpsertSql();

    String taskMessageUpsertSql();

    String taskMessageAttemptUpsertSql();

    String workerUpsertSql();

    String workerContextUpsertSql();

    String ruleUpsertSql();
}
