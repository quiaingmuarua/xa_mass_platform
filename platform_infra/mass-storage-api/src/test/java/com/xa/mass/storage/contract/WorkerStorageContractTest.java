package com.xa.mass.storage.contract;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.storage.api.WorkerStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract that every {@link WorkerStorage} implementation must
 * satisfy. Worker storage is worker-level only; WorkerContext CRUD was retired
 * from the control-plane storage contract.
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
    void updateWorker_movesWorkerBetweenGroupIndexes() {
        storage.addWorker(worker("w1", "grp-a"));
        Worker updated = storage.getWorker("w1").orElseThrow();
        updated.setWorkerGroupId("grp-b");
        assertThat(storage.updateWorker(updated)).isTrue();
        assertThat(storage.getWorkersByGroupId("grp-a")).isEmpty();
        assertThat(storage.getWorkersByGroupId("grp-b"))
                .extracting(Worker::getWorkerId).containsExactly("w1");
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
        assertThat(storage.getAllWorkers()).extracting(Worker::getWorkerId)
                .containsExactlyInAnyOrder("wa", "wb");
    }

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

    protected Worker worker(String workerId, String groupId) {
        Worker worker = new Worker(workerId, "1.0", List.of());
        worker.setWorkerGroupId(groupId);
        worker.setStatus(WorkerStatus.ONLINE);
        return worker;
    }
}
