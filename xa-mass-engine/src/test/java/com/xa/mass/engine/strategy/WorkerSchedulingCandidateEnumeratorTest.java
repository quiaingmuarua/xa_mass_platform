package com.xa.mass.engine.strategy;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WorkerSchedulingCandidateEnumeratorTest {

    @Test
    void createsWorkerLevelCandidateFromRoutingAttributes() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
        Worker worker = worker("worker-stateless", Map.of("routingTags", "shared,us", "country", "us"));
        workerManager.addWorker(workerResource(worker));

        WorkerSchedulingCandidateEnumerator enumerator = new WorkerSchedulingCandidateEnumerator(workerManager);

        List<WorkerSchedulingCandidate> candidates = enumerator.enumerate(List.of(TestWorkerCandidateRows.from(worker)));

        assertEquals(1, candidates.size());
        WorkerSchedulingCandidate candidate = candidates.getFirst();
        assertEquals("worker-stateless", candidate.getWorkerId());
        assertEquals("worker-stateless", candidate.getSchedulingView().schedulingResourceId());
        assertEquals(Set.of("shared", "us"), candidate.getSchedulingView().schedulingRoutingTags());
        assertEquals("us", candidate.getSchedulingView().schedulingAttributes().get("country"));
    }

    @Test
    void enumerationBuildsWorkerLevelCandidateFromWorkerAttributes() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
        Worker worker = worker("worker-attribute-backed", Map.of("country", "worker-level"));
        workerManager.addWorker(workerResource(worker));

        WorkerSchedulingCandidateEnumerator enumerator = new WorkerSchedulingCandidateEnumerator(workerManager);

        List<WorkerSchedulingCandidate> candidates = enumerator.enumerate(List.of(TestWorkerCandidateRows.from(worker)));

        assertEquals(1, candidates.size());
        WorkerSchedulingCandidate candidate = candidates.getFirst();
        assertEquals("worker-attribute-backed", candidate.getWorkerId());
        assertEquals("worker-attribute-backed", candidate.getSchedulingView().schedulingResourceId());
        assertEquals("worker-level", candidate.getSchedulingView().schedulingAttributes().get("country"));
    }

    private Worker worker(String workerId, Map<String, String> attributes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(attributes);
        return worker;
    }

    private WorkerResourceRecord workerResource(Worker worker) {
        return new WorkerResourceRecord(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

}
