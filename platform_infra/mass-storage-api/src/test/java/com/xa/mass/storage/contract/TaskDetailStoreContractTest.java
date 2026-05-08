package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for {@link TaskDetailStore} implementations.
 *
 * <p>The projection records are the owner surface. Deprecated compatibility
 * CRUD methods are covered indirectly through default materialization and no
 * longer define the contract.</p>
 */
public abstract class TaskDetailStoreContractTest {

    protected TaskDetailStore store;

    protected abstract TaskDetailStore createStore();

    protected void destroyStore(TaskDetailStore store) {
    }

    /**
     * Called before adding any messages for a task. Implementations that require
     * a task to be initialized first (for example in-memory and JDBC) should
     * override this to create a minimal task record.
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

    @Test
    void getTaskMessageProjections_withLimit_returnsEmptyList_whenNoneAdded() {
        assertThat(store.getTaskMessageProjections("no-task", 1)).isNotNull().isEmpty();
    }

    @Test
    void getTaskMessageProjection_returnsEmpty_whenNotPresent() {
        assertThat(store.getTaskMessageProjection("no-task", "no-msg")).isEmpty();
    }

    @Test
    void getTaskMessageAttemptProjections_returnsEmptyList_whenNoneAdded() {
        assertThat(store.getTaskMessageAttemptProjections("t1", "m1")).isNotNull().isEmpty();
    }

    @Test
    void getLatestTaskMessageAttemptProjection_returnsEmpty_whenNoneAdded() {
        assertThat(store.getLatestTaskMessageAttemptProjection("t1", "m1")).isEmpty();
    }

    @Test
    void getLatestActiveTaskMessageAttemptProjection_returnsEmpty_whenNoneAdded() {
        assertThat(store.getLatestActiveTaskMessageAttemptProjection("t1", "m1")).isEmpty();
    }

    @Test
    void getTaskMessageStats_returnsZeroTotal_whenNoneAdded() {
        assertThat(store.getTaskMessageStats("no-task").getTotal()).isZero();
    }

    @Test
    void upsertAndGetTaskMessageProjection() {
        assertThat(store.upsertTaskMessageProjection("t1", msg("t1", "m1"))).isTrue();
        assertThat(store.getTaskMessageProjection("t1", "m1")).isPresent()
                .get().extracting(TaskDetailStore.TaskMessageProjection::messageId).isEqualTo("m1");
    }

    @Test
    void getTaskMessageStats_countsAccurately() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageProjection("t1", msg("t1", "m2"));
        assertThat(store.getTaskMessageStats("t1").getTotal()).isEqualTo(2);
    }

    @Test
    void getTaskMessageProjections_withLimit_respectsLimit() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageProjection("t1", msg("t1", "m2"));
        store.upsertTaskMessageProjection("t1", msg("t1", "m3"));
        assertThat(store.getTaskMessageProjections("t1", 2)).hasSize(2);
    }

    @Test
    void upsertTaskMessageProjection_persistsChanges() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        TaskDetailStore.TaskMessageProjection updated = projectionWithStatus(
                store.getTaskMessageProjection("t1", "m1").orElseThrow(),
                TaskMessageProjectionStatus.SUCCESS
        );
        store.upsertTaskMessageProjection("t1", updated);
        assertThat(store.getTaskMessageProjection("t1", "m1")).get()
                .extracting(TaskDetailStore.TaskMessageProjection::status).isEqualTo(TaskMessageProjectionStatus.SUCCESS);
    }

    @Test
    void upsertAndGetTaskMessageAttemptProjection() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a1"));
        assertThat(store.getTaskMessageAttemptProjections("t1", "m1"))
                .extracting(TaskDetailStore.TaskMessageAttemptProjection::attemptId).containsExactly("a1");
    }

    @Test
    void getLatestTaskMessageAttemptProjection_returnsNewest() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a2"));
        assertThat(store.getLatestTaskMessageAttemptProjection("t1", "m1")).isPresent()
                .get().extracting(TaskDetailStore.TaskMessageAttemptProjection::attemptId).isEqualTo("a2");
    }

    @Test
    void upsertTaskMessageAttemptProjection_persistsChanges() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a1"));
        TaskDetailStore.TaskMessageAttemptProjection updated = attemptWithStatus(
                store.getLatestTaskMessageAttemptProjection("t1", "m1").orElseThrow(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );
        store.upsertTaskMessageAttemptProjection("t1", "m1", updated);
        assertThat(store.getLatestTaskMessageAttemptProjection("t1", "m1")).get()
                .extracting(TaskDetailStore.TaskMessageAttemptProjection::status).isEqualTo(TaskMessageAttemptProjectionStatus.SUCCEEDED);
    }

    @Test
    void projectionsPreserveOutputEntriesWithNullValues() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerId", "worker-1");
        output.put("title", null);

        store.upsertTaskMessageProjection("t1", messageWithOutput("t1", "m1", output));
        store.upsertTaskMessageAttemptProjection("t1", "m1",
                attemptWithOutput("t1", "m1", "a1", output));

        assertThat(store.getTaskMessageProjection("t1", "m1")).get()
                .extracting(TaskDetailStore.TaskMessageProjection::output)
                .isEqualTo(output);
        assertThat(store.getLatestTaskMessageAttemptProjection("t1", "m1")).get()
                .extracting(TaskDetailStore.TaskMessageAttemptProjection::output)
                .isEqualTo(output);
    }

    @Test
    void getLatestActiveTaskMessageAttemptProjection_returnsEmpty_whenAllAttemptsFinalized() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a1"));

        TaskDetailStore.TaskMessageAttemptProjection updated = attemptWithStatus(
                store.getLatestTaskMessageAttemptProjection("t1", "m1").orElseThrow(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );
        store.upsertTaskMessageAttemptProjection("t1", "m1", updated);

        assertThat(store.getLatestActiveTaskMessageAttemptProjection("t1", "m1")).isEmpty();
    }

    @Test
    void getLatestActiveTaskMessageAttemptProjection_returnsActive_whenAttemptIsOngoing() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageAttemptProjection("t1", "m1", attempt("t1", "m1", "a1"));
        assertThat(store.getLatestActiveTaskMessageAttemptProjection("t1", "m1")).isPresent()
                .get().extracting(TaskDetailStore.TaskMessageAttemptProjection::attemptId).isEqualTo("a1");
    }

    @Test
    void getTaskMessageStats_countsAreConsistentWithStoredMessages() {
        store.upsertTaskMessageProjection("t1", msg("t1", "m1"));
        store.upsertTaskMessageProjection("t1", msg("t1", "m2", TaskMessageProjectionStatus.SUCCESS));
        store.upsertTaskMessageProjection("t1", msg("t1", "m3", TaskMessageProjectionStatus.FAILED));

        TaskDetailStore.TaskMessageStats stats = store.getTaskMessageStats("t1");
        assertThat(stats.getTotal()).isEqualTo(3);
        assertThat(stats.getSuccess()).isEqualTo(1);
        assertThat(stats.getFailed()).isEqualTo(1);
    }

    protected TaskDetailStore.TaskMessageProjection msg(String taskId, String messageId) {
        initTask(taskId);
        return msg(taskId, messageId, TaskMessageProjectionStatus.INIT);
    }

    protected TaskDetailStore.TaskMessageProjection msg(String taskId,
                                                        String messageId,
                                                        TaskMessageProjectionStatus status) {
        initTask(taskId);
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                Map.of("k", "v"),
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    protected TaskDetailStore.TaskMessageAttemptProjection attempt(String taskId, String messageId, String attemptId) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                taskId,
                messageId,
                1,
                null,
                null,
                null,
                TaskMessageAttemptProjectionStatus.DISPATCHED,
                null,
                null,
                null,
                null
        );
    }

    protected TaskDetailStore.TaskMessageProjection messageWithOutput(String taskId,
                                                                      String messageId,
                                                                      Map<String, Object> output) {
        initTask(taskId);
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                taskId,
                Map.of("k", "v"),
                null,
                TaskMessageProjectionStatus.SUCCESS,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                output,
                null,
                null,
                null,
                null
        );
    }

    protected TaskDetailStore.TaskMessageAttemptProjection attemptWithOutput(String taskId,
                                                                             String messageId,
                                                                             String attemptId,
                                                                             Map<String, Object> output) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                taskId,
                messageId,
                1,
                null,
                null,
                null,
                TaskMessageAttemptProjectionStatus.SUCCEEDED,
                null,
                null,
                null,
                output
        );
    }

    private TaskDetailStore.TaskMessageProjection projectionWithStatus(TaskDetailStore.TaskMessageProjection projection,
                                                                       TaskMessageProjectionStatus status) {
        return new TaskDetailStore.TaskMessageProjection(
                projection.messageId(),
                projection.taskId(),
                projection.input(),
                projection.payloadRef(),
                status,
                projection.assignedTime(),
                projection.createTime(),
                projection.updateTime(),
                projection.startTime(),
                projection.completeTime(),
                projection.retryCount(),
                projection.maxRetryCount(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.finalReason(),
                projection.output(),
                projection.latestAttemptId(),
                projection.latestAttemptWorkerId(),
                projection.latestAttemptWorkerContextId(),
                projection.latestAttemptBatchId()
        );
    }

    private TaskDetailStore.TaskMessageAttemptProjection attemptWithStatus(TaskDetailStore.TaskMessageAttemptProjection projection,
                                                                           TaskMessageAttemptProjectionStatus status) {
        return new TaskDetailStore.TaskMessageAttemptProjection(
                projection.attemptId(),
                projection.taskId(),
                projection.messageId(),
                projection.attemptNo(),
                projection.workerId(),
                projection.workerContextId(),
                projection.batchId(),
                status,
                projection.finalReason(),
                projection.errorMessage(),
                projection.errorCode(),
                projection.output()
        );
    }
}
