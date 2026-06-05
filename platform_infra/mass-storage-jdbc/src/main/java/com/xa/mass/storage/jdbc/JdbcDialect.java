package com.xa.mass.storage.jdbc;

interface JdbcDialect {

    String taskUpsertSql();

    String ruleUpsertSql();

    String principalUpsertSql();

    String catalogEventUpsertSql();

    String catalogProjectUpsertSql();
}
