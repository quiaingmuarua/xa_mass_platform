package com.xa.mass.storage.jdbc;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.util.UUID;

public final class EmbeddedPostgresSupport {

    private static final String USERNAME = "postgres";
    private static final String PASSWORD = "postgres";
    private static final EmbeddedPostgres POSTGRES = start();

    private EmbeddedPostgresSupport() {
    }

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder()
                    .setPGStartupWait(java.time.Duration.ofSeconds(30))
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start embedded postgres", e);
        }
    }

    public static String username() {
        return USERNAME;
    }

    public static String password() {
        return PASSWORD;
    }

    public static String isolatedJdbcUrl(String testId) {
        String database = sanitizeDatabaseName(testId + "_" + UUID.randomUUID());
        try {
            POSTGRES.getPostgresDatabase().getConnection().createStatement()
                    .execute("CREATE DATABASE " + database);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create embedded postgres database " + database, e);
        }
        return POSTGRES.getJdbcUrl(database);
    }

    private static String sanitizeDatabaseName(String input) {
        return input.replace('-', '_').replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
    }
}

