package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.WorkerDeclarationRecord;
import com.xa.mass.storage.api.WorkerDeclarationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract that every {@link WorkerDeclarationStore} implementation must
 * satisfy. Worker declaration storage is declaration-only; runtime heartbeat,
 * status, dispatch, and WorkerContext CRUD do not belong in this contract.
 */
public abstract class WorkerDeclarationStoreContractTest {

    protected WorkerDeclarationStore storage;

    protected abstract WorkerDeclarationStore createStorage();

    protected void destroyStorage(WorkerDeclarationStore storage) {
    }

    @BeforeEach
    void setUp() {
        storage = createStorage();
    }

    @AfterEach
    void tearDown() {
        destroyStorage(storage);
    }

    @Test
    void getWorker_returnsEmpty_whenNotPresent() {
        assertThat(storage.getWorker("no-such-worker")).isEmpty();
    }

    @Test
    void deleteWorker_returnsFalse_whenNotPresent() {
        assertThat(storage.deleteWorker("ghost")).isFalse();
    }

    @Test
    void addAndGetWorker_persists() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.getWorker("w1")).isPresent()
                .get().extracting(WorkerDeclarationRecord::workerGroupId).isEqualTo("grp-a");
    }

    @Test
    void updateWorker_persistsChanges() {
        storage.addWorker(worker("w1", "grp-a"));
        WorkerDeclarationRecord updated = withGroup(storage.getWorker("w1").orElseThrow(), "grp-b");
        storage.updateWorker(updated);
        assertThat(storage.getWorker("w1")).get()
                .extracting(WorkerDeclarationRecord::workerGroupId).isEqualTo("grp-b");
    }

    @Test
    void updateWorker_movesWorkerBetweenGroupIndexes() {
        storage.addWorker(worker("w1", "grp-a"));
        WorkerDeclarationRecord updated = withGroup(storage.getWorker("w1").orElseThrow(), "grp-b");
        assertThat(storage.updateWorker(updated)).isTrue();
        assertThat(storage.getWorkersByGroupId("grp-a")).isEmpty();
        assertThat(storage.getWorkersByGroupId("grp-b"))
                .extracting(WorkerDeclarationRecord::workerId).containsExactly("w1");
    }

    @Test
    void deleteWorker_removesWorker() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.deleteWorker("w1")).isTrue();
        assertThat(storage.getWorker("w1")).isEmpty();
    }

    @Test
    void deleteWorker_removesWorkerFromGroupIndex() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.deleteWorker("w1")).isTrue();
        assertThat(storage.getWorkersByGroupId("grp-a")).isEmpty();
    }

    @Test
    void getAllWorkers_includesAllAdded() {
        storage.addWorker(worker("wa", "grp"));
        storage.addWorker(worker("wb", "grp"));
        assertThat(storage.getAllWorkers()).extracting(WorkerDeclarationRecord::workerId)
                .containsExactlyInAnyOrder("wa", "wb");
    }

    @Test
    void getWorkersByGroupId_returnsOnlyMatchingGroup() {
        storage.addWorker(worker("w-a", "grp-a"));
        storage.addWorker(worker("w-b", "grp-b"));
        assertThat(storage.getWorkersByGroupId("grp-a"))
                .extracting(WorkerDeclarationRecord::workerId).containsExactly("w-a");
    }

    @Test
    void getWorkersByGroupId_returnsEmpty_whenGroupUnknown() {
        storage.addWorker(worker("w1", "grp-a"));
        assertThat(storage.getWorkersByGroupId("no-such-group")).isEmpty();
    }

    protected WorkerDeclarationRecord worker(String workerId, String groupId) {
        return new WorkerDeclarationRecord(
                workerId,
                groupId,
                null,
                null,
                null,
                "1.0",
                1,
                Map.of(),
                null,
                null
        );
    }

    private static WorkerDeclarationRecord withGroup(WorkerDeclarationRecord source, String groupId) {
        return new WorkerDeclarationRecord(
                source.workerId(),
                groupId,
                source.adapterNodeId(),
                source.adapterId(),
                source.onlineStrategy(),
                source.agentVersion(),
                source.maxConcurrentWork(),
                source.attributes(),
                source.createTime(),
                source.updateTime()
        );
    }
}
