package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.storage.api.WorkerStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract that every {@link WorkerStorage} implementation must
 * satisfy. Subclass this and implement {@link #createStorage()} to bind a
 * concrete implementation.
 *
 * <p>These assertions pin the invariants that are easy to diverge between
 * JVM-local, JDBC, and distributed implementations:
 * <ul>
 *   <li>Missing-key queries return empty, never null or an exception</li>
 *   <li>Lock acquisition is exclusive (CAS semantics)</li>
 *   <li>Filters are exact-match, not prefix or fuzzy</li>
 *   <li>getAllWorkerContexts spans all workers, not just one</li>
 * </ul>
 */
public abstract class WorkerStorageContractTest {

    protected WorkerStorage storage;

    protected abstract WorkerStorage createStorage();

    protected void destroyStorage(WorkerStorage storage) {
    }

    @BeforeEach
    void setUp() {
        storage = createStorage();
    }

    @AfterEach
    void tearDown() {
        destroyStorage(storage);
    }

    // ── missing-key safety ────────────────────────────────────────────────────

    @Test
    void getWorker_returnsEmpty_whenNotPresent() {
        assertThat(storage.getWorker("no-such-worker")).isEmpty();
    }

    @Test
    void getWorkerContexts_returnsEmptyList_whenWorkerHasNoContexts() {
        storage.addWorker(worker("w1", "grp"));
        assertThat(storage.getWorkerContexts("w1")).isNotNull().isEmpty();
    }

    @Test
    void getWorkerContexts_returnsEmptyList_whenWorkerDoesNotExist() {
        assertThat(storage.getWorkerContexts("ghost")).isNotNull().isEmpty();
    }

    @Test
    void getWorkerContextById_returnsEmpty_whenNotPresent() {
        assertThat(storage.getWorkerContextById("ghost-ctx")).isEmpty();
    }

    @Test
    void deleteWorker_returnsFalse_whenNotPresent() {
        assertThat(storage.deleteWorker("ghost")).isFalse();
    }

    @Test
    void deleteWorkerContextById_returnsFalse_whenNotPresent() {
        assertThat(storage.deleteWorkerContextById("ghost-ctx")).isFalse();
    }

    // ── worker CRUD ───────────────────────────────────────────────────────────

    @Test
    void addAndGetWorker_persists() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.getWorker("w1")).isPresent()
                .get().extracting(Worker::getWorkerGroupId).isEqualTo("grp-a");
    }

    @Test
    void updateWorker_persistsChanges() {
        storage.addWorker(worker("w1", "grp-a"));
        Worker updated = storage.getWorker("w1").orElseThrow();
        updated.setWorkerGroupId("grp-b");
        storage.updateWorker(updated);
        assertThat(storage.getWorker("w1")).get()
                .extracting(Worker::getWorkerGroupId).isEqualTo("grp-b");
    }

    @Test
    void deleteWorker_removesWorker() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.deleteWorker("w1")).isTrue();
        assertThat(storage.getWorker("w1")).isEmpty();
    }

    @Test
    void getAllWorkers_includesAllAdded() {
        storage.addWorker(worker("wa", "grp"));
        storage.addWorker(worker("wb", "grp"));
        assertThat(storage.getAllWorkers()).extracting(Worker::getWorkerId)
                .containsExactlyInAnyOrder("wa", "wb");
    }

    // ── worker filters ────────────────────────────────────────────────────────

    @Test
    void getWorkersByGroupId_returnsOnlyMatchingGroup() {
        storage.addWorker(worker("w-a", "grp-a"));
        storage.addWorker(worker("w-b", "grp-b"));
        assertThat(storage.getWorkersByGroupId("grp-a"))
                .extracting(Worker::getWorkerId).containsExactly("w-a");
    }

    @Test
    void getWorkersByGroupId_returnsEmpty_whenGroupUnknown() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.getWorkersByGroupId("no-such-group")).isEmpty();
    }

    @Test
    void getWorkersBySupportedProject_filtersCorrectly() {
        Worker supports = worker("w-supports", "grp");
        supports.setSupportedProjects(List.of("proj-x", "proj-y"));
        storage.addWorker(supports);

        Worker doesNot = worker("w-no", "grp");
        doesNot.setSupportedProjects(List.of("proj-z"));
        storage.addWorker(doesNot);

        assertThat(storage.getWorkersBySupportedProject("proj-x"))
                .extracting(Worker::getWorkerId).containsExactly("w-supports");
        assertThat(storage.getWorkersBySupportedProject("proj-z"))
                .extracting(Worker::getWorkerId).containsExactly("w-no");
    }

    @Test
    void getWorkersBySupportedEventCode_filtersCorrectly() {
        Worker supports = worker("w-evt", "grp");
        supports.setSupportedEventCodes(List.of("task.crawl"));
        storage.addWorker(supports);

        Worker doesNot = worker("w-no-evt", "grp");
        doesNot.setSupportedEventCodes(List.of("task.other"));
        storage.addWorker(doesNot);

        assertThat(storage.getWorkersBySupportedEventCode("task.crawl"))
                .extracting(Worker::getWorkerId).containsExactly("w-evt");
    }

    // ── context CRUD ──────────────────────────────────────────────────────────

    @Test
    void addWorkerContext_andGetByWorkerId() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        assertThat(storage.getWorkerContexts("w1"))
                .extracting(WorkerContext::getWorkerContextId).containsExactly("ctx-1");
    }

    @Test
    void getWorkerContextById_returnsContext() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        assertThat(storage.getWorkerContextById("ctx-1")).isPresent()
                .get().extracting(WorkerContext::getWorkerId).isEqualTo("w1");
    }

    @Test
    void updateWorkerContextById_persistsChanges() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        WorkerContext updated = storage.getWorkerContextById("ctx-1").orElseThrow();
        updated.setStatus(WorkerContextStatus.OCCUPIED);
        storage.updateWorkerContextById("ctx-1", updated);
        assertThat(storage.getWorkerContextById("ctx-1")).get()
                .extracting(WorkerContext::getStatus).isEqualTo(WorkerContextStatus.OCCUPIED);
    }

    @Test
    void deleteWorkerContextById_removesContext() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        assertThat(storage.deleteWorkerContextById("ctx-1")).isTrue();
        assertThat(storage.getWorkerContextById("ctx-1")).isEmpty();
        assertThat(storage.getWorkerContexts("w1")).isEmpty();
    }

    @Test
    void getAllWorkerContexts_includesContextsAcrossAllWorkers() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorker(worker("w2", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        storage.addWorkerContext(context("ctx-2", "w2"));
        assertThat(storage.getAllWorkerContexts())
                .extracting(WorkerContext::getWorkerContextId)
                .containsExactlyInAnyOrder("ctx-1", "ctx-2");
    }

    @Test
    void getWorkerContextsByWorkerIds_returnsOnlyRequestedWorkers() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorker(worker("w2", "grp"));
        storage.addWorker(worker("w3", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        storage.addWorkerContext(context("ctx-2", "w2"));
        storage.addWorkerContext(context("ctx-3", "w3"));

        assertThat(storage.getWorkerContextsByWorkerIds(List.of("w1", "w3")))
                .extracting(WorkerContext::getWorkerContextId)
                .containsExactlyInAnyOrder("ctx-1", "ctx-3");
    }

    @Test
    void getWorkerContextsByWorkerIds_returnsEmpty_whenListIsEmpty() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        assertThat(storage.getWorkerContextsByWorkerIds(List.of())).isEmpty();
    }

    @Test
    void getWorkerContextsByWorkerIds_excludesUnknownWorkers() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorkerContext(context("ctx-1", "w1"));
        assertThat(storage.getWorkerContextsByWorkerIds(List.of("w1", "ghost")))
                .extracting(WorkerContext::getWorkerContextId)
                .containsExactly("ctx-1");
    }

    // ── locking — most critical cross-impl contract ───────────────────────────

    @Test
    void tryLockWorker_returnsTrueOnFirstCall() {
        storage.addWorker(worker("w1", "grp"));
        assertThat(storage.tryLockWorker("w1")).isTrue();
    }

    @Test
    void tryLockWorker_isExclusive_secondCallReturnsFalse() {
        storage.addWorker(worker("w1", "grp"));
        storage.tryLockWorker("w1");
        assertThat(storage.tryLockWorker("w1")).isFalse();
    }

    @Test
    void isLocked_reflectsCurrentLockState() {
        storage.addWorker(worker("w1", "grp"));
        assertThat(storage.isLocked("w1")).isFalse();
        storage.tryLockWorker("w1");
        assertThat(storage.isLocked("w1")).isTrue();
    }

    @Test
    void unlockWorker_releasesLock_andAllowsRelocking() {
        storage.addWorker(worker("w1", "grp"));
        storage.tryLockWorker("w1");
        storage.unlockWorker("w1");
        assertThat(storage.isLocked("w1")).isFalse();
        assertThat(storage.tryLockWorker("w1")).isTrue();
    }

    @Test
    void getLockedWorkers_reflectsCurrentLockState() {
        storage.addWorker(worker("w1", "grp"));
        storage.addWorker(worker("w2", "grp"));
        storage.tryLockWorker("w1");
        assertThat(storage.getLockedWorkers()).containsExactly("w1");
        storage.tryLockWorker("w2");
        assertThat(storage.getLockedWorkers()).containsExactlyInAnyOrder("w1", "w2");
        storage.unlockWorker("w1");
        assertThat(storage.getLockedWorkers()).containsExactly("w2");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    protected Worker worker(String workerId, String groupId) {
        Worker w = new Worker(workerId, "1.0", List.of());
        w.setWorkerGroupId(groupId);
        w.setStatus(WorkerStatus.ONLINE);
        return w;
    }

    protected WorkerContext context(String contextId, String workerId) {
        WorkerContext ctx = new WorkerContext(contextId, workerId, Set.of("tag-a"));
        ctx.setStatus(WorkerContextStatus.IDLE);
        return ctx;
    }
}
