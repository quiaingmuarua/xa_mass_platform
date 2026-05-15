package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerSchedulingCandidateTest {

    @Test
    void retainsWorkerLegacyContextAndSchedulingView() {
        Worker worker = worker("worker-1");
        WorkerContext workerContext = context("ctx-1", "worker-1");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(
                worker,
                workerContext,
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(worker, workerContext, schedulingView);

        assertSame(worker, candidate.getWorker());
        assertSame(workerContext, candidate.getWorkerContext());
        assertSame(schedulingView, candidate.getSchedulingView());
        assertEquals("worker-1", candidate.getWorkerId());
        assertEquals("ctx-1", candidate.getWorkerContextId());
    }

    @Test
    void supportsNullLegacyWorkerContext() {
        Worker worker = worker("worker-2");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(
                worker,
                null,
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(worker, null, schedulingView);

        assertSame(worker, candidate.getWorker());
        assertNull(candidate.getWorkerContext());
        assertEquals("worker-2", candidate.getWorkerId());
        assertNull(candidate.getWorkerContextId());
        assertSame(schedulingView, candidate.getSchedulingView());
    }

    @Test
    void rejectsMissingRequiredFields() {
        Worker worker = worker("worker-3");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(
                worker,
                null,
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        assertThrows(NullPointerException.class, () -> new WorkerSchedulingCandidate(null, null, schedulingView));
        assertThrows(NullPointerException.class, () -> new WorkerSchedulingCandidate(worker, null, null));
    }

    private Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        return worker;
    }

    private WorkerContext context(String workerContextId, String workerId) {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId(workerContextId);
        workerContext.setWorkerId(workerId);
        return workerContext;
    }
}
