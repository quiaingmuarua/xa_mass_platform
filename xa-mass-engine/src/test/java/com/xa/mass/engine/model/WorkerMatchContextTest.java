package com.xa.mass.engine.model;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.storage.InMemoryWorkerStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerMatchContextTest {

    @Test
    void contextIncludesNestedReadOnlyAttributesMaps() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("pool", "warmup"));

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-1");
        workerContext.setWorkerId("worker-1");
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerContext.setChannel("us");
        workerContext.setAttributes(Map.of("country", "us"));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject("demoApp");
        task.setTaskRoutingCode("us");
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, workerContext, task, workerManager);

        assertEquals(Map.of("pool", "warmup"), context.getContext().get("workerAttributes"));
        assertEquals(Map.of("country", "us"), context.getContext().get("workerContextAttributes"));
        assertEquals(true, context.getContext().get("hasWorkerContext"));
        assertEquals(0, context.getContext().get("taskTargetNumber"));
        assertEquals("us", context.getContext().get("taskRoutingCode"));
        assertEquals(true, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(true, context.getContext().get("workerGroupIdEqualsRoutingCode"));
        assertEquals(true, context.getContext().get("workerContextChannelMatchesRoutingCode"));
        assertEquals(true, context.getContext().get("workerContextAttributeCountryMatchesRoutingCode"));
        assertEquals(true, context.getContext().get("isWorkerContextAvailable"));
        assertEquals(true, context.getContext().get("isWorkerContextUsable"));
        assertEquals(false, context.getContext().get("isWorkerContextReserved"));
        assertEquals(false, context.getContext().get("isWorkerContextOccupied"));
    }

    @Test
    void contextUsesEmptyWorkerContextAttributesWhenWorkerContextMissing() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-2");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-2");
        task.setProject("demoApp");
        task.setTaskRoutingCode("us");
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, null, task, workerManager);

        assertEquals(Map.of(), context.getContext().get("workerContextAttributes"));
        assertFalse((Boolean) context.getContext().get("hasWorkerContext"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAllocatable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAvailable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextUsable"));
    }

    @Test
    void reservedWorkerContextIsUsableButNotAvailableForNewAssignment() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-3");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-3");
        workerContext.setWorkerId("worker-3");
        workerContext.setStatus(WorkerContextStatus.RESERVED);
        workerContext.setChannel("us");

        Task task = new Task();
        task.setTid("task-3");
        task.setProject("demoApp");
        task.setTaskRoutingCode("us");
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, workerContext, task, workerManager);

        assertFalse((Boolean) context.getContext().get("isWorkerContextAllocatable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerContextUsable"));
        assertTrue((Boolean) context.getContext().get("isWorkerContextReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextOccupied"));
    }

    @Test
    void contextMarksRoutingRequirementFalseWhenTaskHasNoCountryConstraint() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-4");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-4");
        task.setProject("demoApp");
        task.setTaskRoutingCode(null);
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, null, task, workerManager);

        assertEquals(false, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(false, context.getContext().get("workerContextAttributeCountryMatchesRoutingCode"));
        assertEquals(false, context.getContext().get("workerContextChannelMatchesRoutingCode"));
    }
}
