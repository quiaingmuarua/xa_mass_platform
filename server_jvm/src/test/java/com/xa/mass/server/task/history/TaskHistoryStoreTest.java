package com.xa.mass.server.task.history;

import static org.assertj.core.api.Assertions.*;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.*;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.retention.TaskRetentionProperties;
import com.xa.mass.server.testing.TaskCallers;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class TaskHistoryStoreTest {
    @TempDir Path directory;
    private final AtomicLong now = new AtomicLong(10000);
    private final RedisKeyspace space = new RedisKeyspace("test_history");
    private TaskHistoryStore store;
    private TaskHistoryService service;
    private TaskRetentionProperties properties;
    @BeforeEach void open() throws Exception {
        properties = new TaskRetentionProperties(true, Duration.ZERO, Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofHours(1), directory.resolve("历史 data.sqlite"));
        store = new TaskHistoryStore(JsonMapper.builder().build(), properties);
        service = new TaskHistoryService(store, space, JsonMapper.builder().build(), new com.xa.mass.server.operation.OperationGuard(), now::get);
    }
    @AfterEach void close() throws Exception { store.close(); }
    private TaskHistoryStore.Snapshot snapshot(String task, String scope, String project, String creator, long ended) {
        var descriptor = new TaskDescriptor(task, "group", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "1", "maxRetryTimes", "1"), List.of(), "历史任务",
                new TaskAttribution(project, 1, creator).stamp(Map.of("server.inputOwner", "raw")));
        return new TaskHistoryStore.Snapshot(TaskHistoryStore.historyId(scope, task, ended + 1), scope, descriptor,
                "terminal", ended, ended + 1, ended + 100000, new TaskItemScoreCounts(3, Map.of(1, 2L, 6, 1L)));
    }
    private TaskHistoryStore.Snapshot snapshot(String task) { return snapshot(task, space.base(), "p", "1001", now.get() - 2); }
    private TaskHistoryStore.Row row(String id, boolean result) {
        return new TaskHistoryStore.Row(id, new TaskItem(id, "event", 1, Map.of("value", "中文\n😀", "nested", Map.of("one", 1L, "decimal", 1.25, "list", List.of(1L, 2L))), 1, null,
                new WorkerQuery("worker.any", Map.of())), new TaskItemScoreState(1, TaskItemScoreBand.ACTIVE, 1, 1, null),
                result ? TaskItemResult.succeeded("{\"answer\":true}") : null);
    }
    private void publish(TaskHistoryStore.Snapshot snapshot) throws Exception {
        store.begin(snapshot); store.append(snapshot.historyId(), List.of(row("one", true), row("pending", false)));
        store.publish(snapshot.historyId(), now.get());
    }
    @Test void pendingIsInvisibleAndRestartPreservesCompletePublicationAndMissingResults() throws Exception {
        var snapshot = snapshot("t"); store.begin(snapshot);
        store.append(snapshot.historyId(), List.of(row("one", true)));
        assertThat(store.get(space.base(), snapshot.historyId(), now.get())).isNull();
        assertThat(service.list(TaskCallers.caller("1000", "admin"), null, null, null, null, 20).entries()).isEmpty();
        store.close(); store = new TaskHistoryStore(JsonMapper.builder().build(), properties);
        service = new TaskHistoryService(store, space, JsonMapper.builder().build(), new com.xa.mass.server.operation.OperationGuard(), now::get);
        publish(snapshot); // duplicate scan identity must not produce another row
        var caller = TaskCallers.caller("1001", "operator", "p");
        assertThat(service.get(caller, snapshot.historyId()).itemRows()).isEqualTo(2);
        assertThat(service.get(caller, snapshot.historyId()).resultRows()).isEqualTo(1);
        var rows = service.items(caller, snapshot.historyId(), null, 100).entries();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).state().band()).isEqualTo("active");
        assertThat(rows.get(0).result().opaqueResultPayload()).isEqualTo("{\"answer\":true}");
        assertThat(rows.get(1).result()).isNull();
        assertThat(rows.get(0).item().payload()).containsEntry("value", "中文\n😀");
        assertThat(service.get(caller, snapshot.historyId()).counts().total()).isEqualTo(3); // preserve independent Owner count
        assertThatThrownBy(() -> store.append(snapshot.historyId(), List.of(row("late", true)))).isInstanceOf(SQLException.class);
    }
    @Test void aConflictingBatchRollsBackAndCannotChangePreviouslyStoredRows() throws Exception {
        var snapshot = snapshot("t"); store.begin(snapshot); store.append(snapshot.historyId(), List.of(row("original", true)));
        assertThatThrownBy(() -> store.append(snapshot.historyId(), List.of(row("new", true), row("original", false))))
                .isInstanceOf(SQLException.class);
        store.publish(snapshot.historyId(), now.get());
        assertThat(service.get(TaskCallers.caller("1000", "admin"), snapshot.historyId()).itemRows()).isEqualTo(1);
    }
    @Test void equivalentJsonNumbersAndObjectOrderDoNotBreakDuplicateScans() throws Exception {
        var snapshot = snapshot("numbers"); store.begin(snapshot);
        var original = row("one", true);
        store.append(snapshot.historyId(), List.of(original));
        var copied = JsonMapper.builder().build().readValue(JsonMapper.builder().build().writeValueAsString(original), TaskHistoryStore.Row.class);
        store.append(snapshot.historyId(), List.of(copied));
        store.publish(snapshot.historyId(), now.get());
        assertThat(service.get(TaskCallers.caller("1000", "admin"), snapshot.historyId()).itemRows()).isEqualTo(1);
    }
    @Test void permissionsApplyBeforePaginationAndKnownIdsDoNotCrossScope() throws Exception {
        for (int i = 0; i < 8; i++) publish(snapshot("t" + i, space.base(), "p", i % 2 == 0 ? "1001" : "1002", 1000 + i));
        var other = snapshot("other", "xa_mass:test_other", "p", "1001", 5000); publish(other);
        var caller = TaskCallers.caller("1001", "operator", "p");
        var first = service.list(caller, "p", null, null, null, 2);
        assertThat(first.entries()).extracting(TaskHistoryService.Entry::taskId).containsExactly("t6", "t4");
        var second = service.list(caller, "p", null, null, first.nextCursor(), 2);
        assertThat(second.entries()).extracting(TaskHistoryService.Entry::taskId).containsExactly("t2", "t0");
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> service.get(caller, other.historyId())).hasMessageContaining("Task");
        assertThatThrownBy(() -> service.get(TaskCallers.caller("1002", "operator", "p"), first.entries().getFirst().historyId())).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> service.list(caller, "q", null, null, null, 2)).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        assertThatThrownBy(() -> service.items(caller, first.entries().getFirst().historyId(), "invalid cursor", 20)).isInstanceOf(com.xa.mass.server.error.ServerException.class);
    }
    @Test void expiredPublishedPendingAndOldScopesAreDeletedWithoutRedisOrReadRenewal() throws Exception {
        var first = snapshot("one"); publish(first);
        var old = snapshot("old", "xa_mass:old", "p", "1001", 9998); publish(old);
        var pending = snapshot("pending"); store.begin(pending); store.append(pending.historyId(), List.of(row("p", false)));
        service.get(TaskCallers.caller("1000", "admin"), first.historyId());
        now.set(first.expiresAtMillis());
        assertThat(store.get(space.base(), first.historyId(), now.get())).isNull();
        int batches = 0; while (store.deleteExpiredBatch(now.get()) > 0) batches++;
        assertThat(batches).isEqualTo(3);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + properties.databasePath()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM task_history")) {
            rows.next(); assertThat(rows.getLong(1)).isZero();
        }
    }
    @Test void rawExportIsCompleteBeyondPreviewAndExcludesFailureAndMissingResults() throws Exception {
        var snapshot = snapshot("big"); store.begin(snapshot);
        for (int i = 0; i < 123; i++) store.append(snapshot.historyId(), List.of(row("id-%03d".formatted(i), true)));
        store.append(snapshot.historyId(), List.of(new TaskHistoryStore.Row("failed", null, null, TaskItemResult.failed()), row("missing", false)));
        store.publish(snapshot.historyId(), now.get());
        var caller = TaskCallers.caller("1000", "admin");
        assertThat(service.preview(caller, snapshot.historyId()).results()).hasSize(100);
        assertThat(service.preview(caller, snapshot.historyId()).truncated()).isTrue();
        var file = service.export(caller, snapshot.historyId()).file();
        assertThat(Files.readAllLines(file)).hasSize(123);
        var output = new java.io.ByteArrayOutputStream(); service.transferAndDelete(file, output);
        assertThat(Files.exists(file)).isFalse();
    }
    @Test void admittedReaderRetainsACompleteSnapshotDuringExpiryDeletion() throws Exception {
        var snapshot = snapshot("t"); publish(snapshot);
        var read = new ArrayList<TaskHistoryStore.Row>();
        store.forEachResult(space.base(), snapshot.historyId(), now.get(), saved -> {
            assertThat(saved).isNotNull();
            try { assertThat(store.deleteExpiredBatch(snapshot.expiresAtMillis())).isPositive(); }
            catch (SQLException failure) { throw new RuntimeException(failure); }
        }, read::add);
        assertThat(read).hasSize(1);
        assertThat(store.get(space.base(), snapshot.historyId(), now.get())).isNull();
    }
    @Test void itemCursorDoesNotExposeOrLimitArbitrarilyLongValidMessageIds() throws Exception {
        var snapshot = snapshot("long-ids"); store.begin(snapshot);
        String longId = "消息".repeat(2000);
        store.append(snapshot.historyId(), List.of(row(longId, true), row("second", false)));
        store.publish(snapshot.historyId(), now.get());
        var caller = TaskCallers.caller("1000", "admin");
        var first = service.items(caller, snapshot.historyId(), null, 1);
        assertThat(first.entries().getFirst().messageId()).isEqualTo(longId);
        assertThat(first.nextCursor()).hasSizeLessThan(100);
        var second = service.items(caller, snapshot.historyId(), first.nextCursor(), 1);
        assertThat(second.entries().getFirst().messageId()).isEqualTo("second");
        assertThat(second.nextCursor()).isNull();
    }
    @Test void busyWriterFailsWithoutPartialPublicationAndRetryUsesSameIdentity() throws Exception {
        var snapshot = snapshot("locked"); store.begin(snapshot);
        try (var blocker = DriverManager.getConnection("jdbc:sqlite:" + properties.databasePath()); var statement = blocker.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertThatThrownBy(() -> store.append(snapshot.historyId(), List.of(row("one", true)))).isInstanceOf(SQLException.class);
            statement.execute("ROLLBACK");
        }
        assertThat(store.get(space.base(), snapshot.historyId(), now.get())).isNull();
        publish(snapshot);
        assertThat(store.get(space.base(), snapshot.historyId(), now.get())).isNotNull();
    }
    @Test void frozenIdentityAndRetentionConfigurationDoNotSilentlyChange() throws Exception {
        assertThat(TaskHistoryStore.historyId(space.base(), "same", 1)).isNotEqualTo(TaskHistoryStore.historyId(space.base(), "same", 2));
        assertThatThrownBy(() -> new TaskRetentionProperties(true, Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(6), Duration.ofHours(1), directory.resolve("x")))
                .isInstanceOf(IllegalArgumentException.class);
        var first = snapshot("same"); store.begin(first);
        var changed = new TaskHistoryStore.Snapshot(first.historyId(), first.scope(), first.task(), first.scoreBand(), first.terminatedAtMillis(), first.frozenAtMillis(), first.expiresAtMillis() + 1, first.counts());
        assertThatThrownBy(() -> store.begin(changed)).isInstanceOf(SQLException.class);
    }
    @Test void unsupportedSchemaIsRejectedWithoutChangingItsJournalMode() throws Exception {
        store.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + properties.databasePath()); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE"); statement.execute("PRAGMA user_version=99");
        }
        assertThatThrownBy(() -> new TaskHistoryStore(JsonMapper.builder().build(), properties)).isInstanceOf(SQLException.class);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + properties.databasePath()); var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA journal_mode")) {
            rows.next(); assertThat(rows.getString(1)).isEqualTo("delete");
        }
    }
    @Test void concurrentExportRejectsBeforeReadingAnotherCopyAndRetryCanRead() throws Exception {
        var snapshot = snapshot("export"); publish(snapshot);
        var caller = TaskCallers.caller("1000", "admin");
        service.forEachResult(caller, snapshot.historyId(), row -> {
            assertThatThrownBy(() -> service.forEachResult(caller, snapshot.historyId(), ignored -> { }))
                    .isInstanceOfSatisfying(com.xa.mass.server.error.ServerException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(com.xa.mass.server.error.ServerErrorCode.TASK_STATE_CONFLICT));
        });
        assertThat(service.preview(caller, snapshot.historyId()).results()).hasSize(1);
    }
}
