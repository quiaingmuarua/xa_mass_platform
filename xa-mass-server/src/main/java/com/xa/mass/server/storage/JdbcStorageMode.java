package com.xa.mass.server.storage;

public enum JdbcStorageMode {
    MEMORY(false),
    JDBC_H2(true),
    JDBC_POSTGRES(true);

    private final boolean jdbc;

    JdbcStorageMode(boolean jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isJdbc() {
        return jdbc;
    }

    public JdbcDialect dialect() {
        return switch (this) {
            case JDBC_H2 -> new H2JdbcDialect();
            case JDBC_POSTGRES -> new PostgresJdbcDialect();
            case MEMORY -> throw new IllegalStateException("memory mode does not have a JDBC dialect");
        };
    }

    public static JdbcStorageMode parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return MEMORY;
        }
        return switch (rawValue.trim().toLowerCase()) {
            case "memory" -> MEMORY;
            case "jdbc-h2" -> JDBC_H2;
            case "jdbc-postgres" -> JDBC_POSTGRES;
            default -> throw new IllegalArgumentException("Unsupported mass.storage.mode: " + rawValue);
        };
    }
}
