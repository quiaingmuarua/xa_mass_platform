package com.xa.mass.storage.jdbc;

interface JdbcDialect {

    String taskUpsertSql();

    String workerUpsertSql();

    String workerContextUpsertSql();

    String ruleUpsertSql();

    String principalUpsertSql();
}

