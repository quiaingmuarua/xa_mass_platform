package com.xa.mass.server.storage;

interface JdbcDialect {

    String taskUpsertSql();

    String workerUpsertSql();

    String workerContextUpsertSql();

    String ruleUpsertSql();

    String principalUpsertSql();
}
