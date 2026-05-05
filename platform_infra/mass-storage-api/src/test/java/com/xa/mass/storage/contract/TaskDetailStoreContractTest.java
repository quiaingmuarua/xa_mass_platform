package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.storage.api.TaskDetailStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for {@link TaskDetailStore} implementations.
 *
 * <p>Semantic traps that differ between implementations:
 * <ul>
 *   <li>getTaskMessages/getTaskMessageAttempts on an unknown key return empty
 *       list, never null or an exception</li>
 *   <li>getNonFinalTaskMessages excludes SUCCESS/FAILED/EXPIRED messages</li>
 *   <li>getLatestActiveTaskMessageAttempt returns empty once the attempt is finalized</li>
 *   <li>getTaskMessageStats counters are consistent with the stored messages</li>
 * </ul>
 */
public abstract class TaskDetailStoreContractTest {

    protected TaskDetailStore store;

    protected abstract TaskDetailStore createStore();

    protected void destroyStore(TaskDetailStore store) {
    }

    /**
     * Called before adding any messages for a task. Implementations that require
     * a task to be initialized first (e.g. in-memory and JDBC) should override
     * this to create a minimal task record.
     */
    protected void initTask(String taskId) {
    }

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    @AfterEach
    void tearDown() {
        destroyStore(store);
    }

    // ── missing-key safety ────────────────────────────────────────────────────

    @Test
    void getTaskMessages_returnsEmptyList_whenNoneAdded() {
        assertThat(store.getTaskMessages("no-task")).isNotNull().isEmpty();
    }

    @Test
    void getTaskMessage_returnsEmpty_whenNotPresent() {
        assertThat(store.getTaskMessage("no-task", "no-msg")).isEmpty();
    }

    @Test
    void getTaskMessageAttempts_returnsEmptyList_whenNoneAdded() {
        assertThat(store.getTaskMessageAttempts("t1", "m1")).isNotNull().isEmpty();
    }

    @Test
    void getLatestTaskMessageAttempt_returnsEmpty_whenNoneAdded() {
        assertThat(store.getLatestTaskMessageAttempt("t1", "m1")).isEmpty();
    }

    @Test
    void getLatestActiveTaskMessageAttempt_returnsEmpty_whenNoneAdded() {
        assertThat(store.getLatestActiveTaskMessageAttempt("t1", "m1")).isEmpty();
    }

    @Test
    void countTaskMessages_returnsZero_whenNoneAdded() {
        assertThat(store.countTaskMessages("no-task")).isZero();
    }

    // ── message CRUD ──────────────────────────────────────────────────────────

    @Test
    void addAndGetTaskMessage() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        assertThat(store.getTaskMessage("t1", "m1")).isPresent()
                .get().extracting(TaskMsg::getMessageId).isEqualTo("m1");
    }

    @Test
    void countTaskMessages_countsAccurately() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessage("t1", msg("t1", "m2"));
        assertThat(store.countTaskMessages("t1")).isEqualTo(2);
    }

    @Test
    void getTaskMessages_withLimit_respectsLimit() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessage("t1", msg("t1", "m2"));
        store.addTaskMessage("t1", msg("t1", "m3"));
        assertThat(store.getTaskMessages("t1", 2)).hasSize(2);
    }

    @Test
    void updateTaskMessage_persistsChanges() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        TaskMsg updated = store.getTaskMessage("t1", "m1").orElseThrow();
        updated.setStatus(TaskMsgStatus.SUCCESS);
        store.updateTaskMessage("t1", updated);
        assertThat(store.getTaskMessage("t1", "m1")).get()
                .extracting(TaskMsg::getStatus).isEqualTo(TaskMsgStatus.SUCCESS);
    }

    // ── getNonFinalTaskMessages filters correctly ──────────────────────────────

    @Test
    void getNonFinalTaskMessages_excludesTerminalMessages() {
        store.addTaskMessage("t1", msg("t1", "m-init"));    // INIT — non-final

        TaskMsg success = msg("t1", "m-success");
        success.setStatus(TaskMsgStatus.SUCCESS);
        store.addTaskMessage("t1", success);

        TaskMsg failed = msg("t1", "m-failed");
        failed.setStatus(TaskMsgStatus.FAILED);
        store.addTaskMessage("t1", failed);

        assertThat(store.getNonFinalTaskMessages("t1"))
                .extracting(TaskMsg::getMessageId).containsExactly("m-init");
    }

    // ── attempt CRUD ──────────────────────────────────────────────────────────

    @Test
    void addAndGetTaskMessageAttempt() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessageAttempt("t1", "m1", attempt("t1", "m1", "a1"));
        assertThat(store.getTaskMessageAttempts("t1", "m1"))
                .extracting(TaskMsgAttempt::getAttemptId).containsExactly("a1");
    }

    @Test
    void getLatestTaskMessageAttempt_returnsNewest() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessageAttempt("t1", "m1", attempt("t1", "m1", "a1"));
        store.addTaskMessageAttempt("t1", "m1", attempt("t1", "m1", "a2"));
        assertThat(store.getLatestTaskMessageAttempt("t1", "m1")).isPresent()
                .get().extracting(TaskMsgAttempt::getAttemptId).isEqualTo("a2");
    }

    @Test
    void updateTaskMessageAttempt_persistsChanges() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessageAttempt("t1", "m1", attempt("t1", "m1", "a1"));
        TaskMsgAttempt updated = store.getLatestTaskMessageAttempt("t1", "m1").orElseThrow();
        updated.setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        store.updateTaskMessageAttempt("t1", "m1", updated);
        assertThat(store.getLatestTaskMessageAttempt("t1", "m1")).get()
                .extracting(TaskMsgAttempt::getStatus).isEqualTo(TaskMsgAttemptStatus.SUCCEEDED);
    }

    // ── getLatestActiveTaskMessageAttempt: only returns active (non-final) attempts ─

    @Test
    void getLatestActiveTaskMessageAttempt_returnsEmpty_whenAllAttemptsFinalized() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        TaskMsgAttempt attempt = attempt("t1", "m1", "a1");
        store.addTaskMessageAttempt("t1", "m1", attempt);

        // finalize the attempt
        TaskMsgAttempt updated = store.getLatestTaskMessageAttempt("t1", "m1").orElseThrow();
        updated.setStatus(TaskMsgAttemptStatus.SUCCEEDED);
        store.updateTaskMessageAttempt("t1", "m1", updated);

        assertThat(store.getLatestActiveTaskMessageAttempt("t1", "m1")).isEmpty();
    }

    @Test
    void getLatestActiveTaskMessageAttempt_returnsActive_whenAttemptIsOngoing() {
        store.addTaskMessage("t1", msg("t1", "m1"));
        store.addTaskMessageAttempt("t1", "m1", attempt("t1", "m1", "a1"));
        assertThat(store.getLatestActiveTaskMessageAttempt("t1", "m1")).isPresent()
                .get().extracting(TaskMsgAttempt::getAttemptId).isEqualTo("a1");
    }

    // ── stats consistency ─────────────────────────────────────────────────────

    @Test
    void getTaskMessageStats_countsAreConsistentWithStoredMessages() {
        store.addTaskMessage("t1", msg("t1", "m1"));  // INIT → processing=0, success=0, failed=0

        TaskMsg success = msg("t1", "m2");
        success.setStatus(TaskMsgStatus.SUCCESS);
        store.addTaskMessage("t1", success);

        TaskMsg failed = msg("t1", "m3");
        failed.setStatus(TaskMsgStatus.FAILED);
        store.addTaskMessage("t1", failed);

        TaskDetailStore.TaskMessageStats stats = store.getTaskMessageStats("t1");
        assertThat(stats.getTotal()).isEqualTo(3);
        assertThat(stats.getSuccess()).isEqualTo(1);
        assertThat(stats.getFailed()).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    protected TaskMsg msg(String taskId, String messageId) {
        initTask(taskId);
        return new TaskMsg(messageId, taskId, Map.of("k", "v"));
    }

    protected TaskMsgAttempt attempt(String taskId, String messageId, String attemptId) {
        return new TaskMsgAttempt(attemptId, taskId, messageId, 1);
    }
}
