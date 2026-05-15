package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class WorkerSchedulingCandidateEnumeratorTest {

    @Test
    void createsWorkerLevelCandidateWhenNoLegacyContextExists() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = worker("worker-stateless", Map.of("routingTags", "shared,us", "country", "us"));
        workerManager.addWorker(worker);

        WorkerSchedulingCandidateEnumerator enumerator = new WorkerSchedulingCandidateEnumerator(workerManager);

        List<WorkerSchedulingCandidate> candidates = enumerator.enumerate(List.of(worker));

        assertEquals(1, candidates.size());
        WorkerSchedulingCandidate candidate = candidates.getFirst();
        assertSame(worker, candidate.getWorker());
        assertNull(candidate.getWorkerContext());
        assertNull(candidate.getWorkerContextId());
        assertEquals("worker-stateless", candidate.getSchedulingView().schedulingResourceId());
        assertEquals(Set.of("shared", "us"), candidate.getSchedulingView().schedulingRoutingTags());
        assertEquals("us", candidate.getSchedulingView().schedulingAttributes().get("country"));
    }

    @Test
    void expandsLegacyContextBackedCandidatesOnlyInsideEnumerator() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = worker("worker-context-backed", Map.of("country", "worker-level"));
        WorkerContext context = workerContext("ctx-1", "worker-context-backed", "us", Map.of("country", "context-level"));
        workerManager.addWorker(worker);
        workerManager.addWorkerContext(context);

        WorkerSchedulingCandidateEnumerator enumerator = new WorkerSchedulingCandidateEnumerator(workerManager);

        List<WorkerSchedulingCandidate> candidates = enumerator.enumerate(List.of(worker));

        assertEquals(1, candidates.size());
        WorkerSchedulingCandidate candidate = candidates.getFirst();
        assertSame(worker, candidate.getWorker());
        assertSame(context, candidate.getWorkerContext());
        assertEquals("ctx-1", candidate.getWorkerContextId());
        assertEquals("ctx-1", candidate.getSchedulingView().schedulingResourceId());
        assertEquals(Set.of("us"), candidate.getSchedulingView().schedulingRoutingTags());
        assertEquals("context-level", candidate.getSchedulingView().schedulingAttributes().get("country"));
    }

    private Worker worker(String workerId, Map<String, String> attributes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(attributes);
        return worker;
    }

    private WorkerContext workerContext(String workerContextId,
                                        String workerId,
                                        String routingTag,
                                        Map<String, String> attributes) {
        WorkerContext context = new WorkerContext();
        context.setWorkerContextId(workerContextId);
        context.setWorkerId(workerId);
        context.setStatus(WorkerContextStatus.IDLE);
        context.setRoutingTags(Set.of(routingTag));
        context.setAttributes(attributes);
        return context;
    }
}
