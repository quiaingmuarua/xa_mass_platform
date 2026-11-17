package com.xa.mass.server.task.history;

import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreState;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.task.retention.TaskRetentionProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Server-owned immutable finite history. SQL never participates in execution admission. */
@Service
public final class TaskHistoryStore implements AutoCloseable {
    private static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper;
    private final String url;
    private final Connection writer;

    public record Snapshot(String historyId, String scope, TaskDescriptor task, String scoreBand,
            long terminatedAtMillis, long frozenAtMillis, long expiresAtMillis, TaskItemScoreCounts counts) { }
    public record Saved(Snapshot snapshot, long publishedAtMillis, long itemRows, long resultRows) { }
    public record Row(String messageId, @Nullable TaskItem item, @Nullable TaskItemScoreState state,
            @Nullable TaskItemResult result) { }
    public record Page<T>(List<T> entries, @Nullable String nextCursor) { }
    private record ListCursor(long terminatedAtMillis, String historyId) { }

    public TaskHistoryStore(ObjectMapper mapper, TaskRetentionProperties properties) throws IOException, SQLException {
        this.mapper = mapper;
        Files.createDirectories(properties.databasePath().getParent());
        url = "jdbc:sqlite:" + properties.databasePath();
        writer = connect();
        try { initialize(); }
        catch (SQLException | RuntimeException failure) { writer.close(); throw failure; }
    }

    private Connection connect() throws SQLException {
        var connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=FULL");
        } catch (SQLException failure) { connection.close(); throw failure; }
        return connection;
    }

    private void initialize() throws SQLException {
        try (var statement = writer.createStatement()) {
            int version;
            try (var rows = statement.executeQuery("PRAGMA user_version")) { rows.next(); version = rows.getInt(1); }
            if (version != 0 && version != SCHEMA_VERSION) throw new SQLException("Unsupported Task history schema");
            if (version == 0) transaction(connection -> {
                try (var schema = connection.createStatement()) {
                    // A versionless existing database must not be adopted as our history database.
                    try (var tables = schema.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                        if (tables.next()) throw new SQLException("Task history database is not empty and has no schema version");
                    }
                    schema.execute("""
                            CREATE TABLE task_history (
                              history_id TEXT PRIMARY KEY, scope TEXT NOT NULL, task_id TEXT NOT NULL,
                              project_id TEXT, creator_id TEXT, input_owner TEXT,
                              terminated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                              snapshot TEXT NOT NULL, published_at INTEGER,
                              item_rows INTEGER NOT NULL DEFAULT 0, result_rows INTEGER NOT NULL DEFAULT 0)
                            """);
                    schema.execute("CREATE INDEX history_listing ON task_history(scope, terminated_at DESC, history_id DESC)");
                    schema.execute("CREATE INDEX history_project_listing ON task_history(scope, project_id, creator_id, terminated_at DESC, history_id DESC)");
                    schema.execute("CREATE INDEX history_expiry ON task_history(expires_at, history_id)");
                    schema.execute("""
                            CREATE TABLE task_history_rows (
                              history_id TEXT NOT NULL REFERENCES task_history(history_id) ON DELETE CASCADE,
                              message_id TEXT NOT NULL, ordinal INTEGER NOT NULL, item TEXT, item_state TEXT, result TEXT,
                              PRIMARY KEY(history_id, message_id), UNIQUE(history_id,ordinal), CHECK(item IS NOT NULL OR result IS NOT NULL))
                            """);
                    schema.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                }
                return null;
            });
            // Fail at startup if a declared schema is missing its required columns.
            statement.executeQuery("SELECT snapshot,published_at,item_rows,result_rows FROM task_history LIMIT 0").close();
            statement.executeQuery("SELECT ordinal,item,item_state,result FROM task_history_rows LIMIT 0").close();
            try (var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) throw new SQLException("Task history requires WAL mode");
            }
        }
    }

    public static String historyId(String scope, String taskId, long frozenAtMillis) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of("finite-history/v1", scope, taskId, Long.toString(frozenAtMillis))) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "history-" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A publication retry never replaces an existing snapshot or a published row. */
    public synchronized void begin(Snapshot snapshot) throws SQLException {
        transaction(connection -> {
            var attribution = com.xa.mass.server.task.directory.TaskAttribution.read(snapshot.task().metadata());
            try (var insert = connection.prepareStatement("""
                    INSERT INTO task_history(history_id,scope,task_id,project_id,creator_id,input_owner,terminated_at,expires_at,snapshot)
                    VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(history_id) DO NOTHING
                    """)) {
                bind(insert, snapshot.historyId(), snapshot.scope(), snapshot.task().taskId(),
                        attribution == null ? null : attribution.projectId(), attribution == null ? null : attribution.userId(),
                        snapshot.task().metadata().get("server.inputOwner"), snapshot.terminatedAtMillis(),
                        snapshot.expiresAtMillis(), mapper.writeValueAsString(snapshot));
                insert.executeUpdate();
            }
            try (var query = connection.prepareStatement("SELECT snapshot FROM task_history WHERE history_id=?")) {
                query.setString(1, snapshot.historyId());
                try (var row = query.executeQuery()) {
                    if (!row.next() || !equalJson(row.getString(1), json(snapshot)))
                        throw new SQLException("Frozen Task history identity differs");
                }
            }
            return null;
        });
    }

    public synchronized void append(String historyId, List<Row> rows) throws SQLException {
        if (rows.size() > 100) throw new IllegalArgumentException("History writes accept at most 100 identities");
        transaction(connection -> {
            try (var check = connection.prepareStatement("SELECT published_at FROM task_history WHERE history_id=?")) {
                check.setString(1, historyId);
                try (var state = check.executeQuery()) {
                    if (!state.next() || state.getObject(1) != null) throw new SQLException("History is not pending publication");
                }
            }
            long ordinal;
            try (var next = connection.prepareStatement("SELECT COALESCE(MAX(ordinal),0) FROM task_history_rows WHERE history_id=?")) {
                next.setString(1, historyId);
                try (var result = next.executeQuery()) { result.next(); ordinal = result.getLong(1); }
            }
            try (var insert = connection.prepareStatement("INSERT INTO task_history_rows(history_id,message_id,ordinal,item,item_state,result) VALUES(?,?,?,?,?,?) ON CONFLICT(history_id,message_id) DO NOTHING");
                 var query = connection.prepareStatement("SELECT message_id,item,item_state,result FROM task_history_rows WHERE history_id=? AND message_id=?")) {
                for (var row : rows) {
                    if (row.messageId() == null || row.messageId().isBlank() || row.item() == null && row.result() == null
                            || row.item() != null && !row.messageId().equals(row.item().messageId()))
                        throw new IllegalArgumentException("Invalid history input association");
                    bind(insert, historyId, row.messageId(), ++ordinal, json(row.item()), json(row.state()), json(row.result()));
                    insert.executeUpdate();
                    bind(query, historyId, row.messageId());
                    try (var existing = query.executeQuery()) {
                        if (!existing.next() || !row.messageId().equals(existing.getString(1))
                                || !equalJson(existing.getString(2), json(row.item()))
                                || !equalJson(existing.getString(3), json(row.state()))
                                || !equalJson(existing.getString(4), json(row.result())))
                            throw new SQLException("Frozen history row differs");
                    }
                }
            }
            return null;
        });
    }

    /** Called only after both complete frozen Owner traversals have returned successfully. */
    public synchronized void publish(String historyId, long now) throws SQLException {
        transaction(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE task_history SET published_at=?,
                      item_rows=(SELECT count(*) FROM task_history_rows WHERE history_id=? AND item IS NOT NULL),
                      result_rows=(SELECT count(*) FROM task_history_rows WHERE history_id=? AND result IS NOT NULL)
                    WHERE history_id=? AND published_at IS NULL AND expires_at>?
                    """)) {
                bind(update, now, historyId, historyId, historyId, now);
                if (update.executeUpdate() != 1) throw new SQLException("History publication expired or is no longer pending");
            }
            return null;
        });
    }

    public @Nullable Saved get(String scope, String historyId, long now) throws SQLException {
        try (var connection = connect()) { return get(connection, scope, historyId, now); }
    }
    private @Nullable Saved get(Connection connection, String scope, String id, long now) throws SQLException {
        try (var query = connection.prepareStatement("SELECT snapshot,published_at,item_rows,result_rows FROM task_history WHERE scope=? AND history_id=? AND published_at IS NOT NULL AND expires_at>?")) {
            bind(query, scope, id, now);
            try (var rows = query.executeQuery()) { return rows.next() ? readSaved(rows) : null; }
        }
    }

    public Page<Saved> list(String scope, @Nullable String projectId, @Nullable String creatorId,
            @Nullable String taskId, @Nullable String cursor, int limit, long now) throws SQLException {
        var values = new ArrayList<Object>(List.of(scope, now));
        var sql = new StringBuilder("SELECT snapshot,published_at,item_rows,result_rows FROM task_history WHERE scope=? AND expires_at>? AND published_at IS NOT NULL");
        if (projectId != null) { sql.append(" AND project_id=?"); values.add(projectId); }
        if (creatorId != null) { sql.append(" AND creator_id=?"); values.add(creatorId); }
        if (taskId != null) { sql.append(" AND task_id=?"); values.add(taskId); }
        if (cursor != null) {
            var after = decode(cursor, ListCursor.class);
            sql.append(" AND (terminated_at<? OR (terminated_at=? AND history_id<?))");
            values.add(after.terminatedAtMillis()); values.add(after.terminatedAtMillis()); values.add(after.historyId());
        }
        sql.append(" ORDER BY terminated_at DESC,history_id DESC LIMIT ?"); values.add(limit + 1);
        try (var connection = connect(); var query = connection.prepareStatement(sql.toString())) {
            bind(query, values.toArray());
            var entries = new ArrayList<Saved>();
            try (var rows = query.executeQuery()) { while (rows.next()) entries.add(readSaved(rows)); }
            String next = null;
            if (entries.size() > limit) {
                entries.removeLast(); var last = entries.getLast().snapshot();
                next = encode(new ListCursor(last.terminatedAtMillis(), last.historyId()));
            }
            return new Page<>(List.copyOf(entries), next);
        }
    }

    /** One SQLite read snapshot spans header authorization and rows, including a concurrent expiry deletion. */
    public Page<Row> rows(String scope, String historyId, @Nullable String cursor, int limit,
            boolean resultsOnly, long now, Consumer<Saved> authorize) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            authorize.accept(get(connection, scope, historyId, now));
            long after = cursor == null ? 0 : decode(cursor, Long.class);
            if (after < 0) throw new IllegalArgumentException("Invalid history cursor");
            try (var query = connection.prepareStatement("SELECT message_id,item,item_state,result,ordinal FROM task_history_rows WHERE history_id=? AND ordinal>?"
                    + (resultsOnly ? " AND result IS NOT NULL" : "") + " ORDER BY ordinal LIMIT ?")) {
                bind(query, historyId, after, limit + 1);
                var entries = new ArrayList<Row>();
                long lastOrdinal = after;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        entries.add(readRow(rows));
                        if (entries.size() <= limit) lastOrdinal = rows.getLong(5);
                    }
                }
                String next = null;
                if (entries.size() > limit) { entries.removeLast(); next = encode(lastOrdinal); }
                return new Page<>(List.copyOf(entries), next);
            }
        }
    }

    public void forEachResult(String scope, String historyId, long now, Consumer<Saved> authorize,
            Consumer<Row> consumer) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            authorize.accept(get(connection, scope, historyId, now));
            try (var query = connection.prepareStatement("SELECT message_id,item,item_state,result FROM task_history_rows WHERE history_id=? AND result IS NOT NULL ORDER BY message_id")) {
                query.setString(1, historyId);
                try (var rows = query.executeQuery()) { while (rows.next()) consumer.accept(readRow(rows)); }
            }
        }
    }

    /** No Redis discovery: the database owns expiry for all of its scopes, including pending publications. */
    public synchronized int deleteExpiredBatch(long now) throws SQLException {
        return transaction(connection -> {
            String id;
            try (var select = connection.prepareStatement("SELECT history_id FROM task_history WHERE expires_at<=? ORDER BY expires_at,history_id LIMIT 1")) {
                select.setLong(1, now);
                try (var rows = select.executeQuery()) { if (!rows.next()) return 0; id = rows.getString(1); }
            }
            int removed;
            try (var delete = connection.prepareStatement("DELETE FROM task_history_rows WHERE history_id=? AND message_id IN (SELECT message_id FROM task_history_rows WHERE history_id=? LIMIT 1000)")) {
                bind(delete, id, id); removed = delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM task_history WHERE history_id=? AND NOT EXISTS(SELECT 1 FROM task_history_rows WHERE history_id=?)")) {
                bind(delete, id, id); removed += delete.executeUpdate();
            }
            return removed;
        });
    }

    private Saved readSaved(ResultSet row) throws SQLException {
        return new Saved(mapper.readValue(row.getString(1), Snapshot.class), row.getLong(2), row.getLong(3), row.getLong(4));
    }
    private Row readRow(ResultSet row) throws SQLException {
        return new Row(row.getString(1), read(row.getString(2), TaskItem.class),
                read(row.getString(3), TaskItemScoreState.class), read(row.getString(4), TaskItemResult.class));
    }
    private <T> @Nullable T read(@Nullable String value, Class<T> type) { return value == null ? null : mapper.readValue(value, type); }
    private @Nullable String json(@Nullable Object value) { return value == null ? null : mapper.writeValueAsString(value); }
    // Compare JSON values, not Java map Number classes or object-key iteration order.
    // Opaque Result payloads remain JSON strings and are compared without interpreting their content.
    boolean sameContent(Object left, Object right) { return equalJson(json(left), json(right)); }
    private boolean equalJson(@Nullable String left, @Nullable String right) {
        return left == null || right == null ? left == right : mapper.readTree(left).equals(mapper.readTree(right));
    }
    private String encode(Object value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value)); }
    private <T> T decode(String value, Class<T> type) {
        try {
            if (value.length() > 2048) throw new IllegalArgumentException("Cursor too long");
            T decoded = mapper.readValue(Base64.getUrlDecoder().decode(value), type);
            if (decoded == null) throw new IllegalArgumentException("Null cursor");
            return decoded;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid history cursor", invalid); }
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }
    private <T> T transaction(SqlWork<T> work) throws SQLException {
        writer.setAutoCommit(false);
        try { T result = work.run(writer); writer.commit(); return result; }
        catch (SQLException | RuntimeException failure) { try { writer.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); } throw failure; }
        finally { writer.setAutoCommit(true); }
    }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    @Override public synchronized void close() throws SQLException { writer.close(); }
}
