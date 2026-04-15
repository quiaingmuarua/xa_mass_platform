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
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, workerContext, task, workerManager);

        assertEquals(Map.of("pool", "warmup"), context.getContext().get("workerAttributes"));
        assertEquals(Map.of("country", "us"), context.getContext().get("workerContextAttributes"));
        assertEquals(0, context.getContext().get("taskTargetNumber"));
        assertEquals("us", context.getContext().get("taskRoutingCountryCode"));
        assertEquals(true, context.getContext().get("workerGroupIdEqualsRoutingCountry"));
        assertEquals(true, context.getContext().get("workerContextChannelMatchesRoutingCountry"));
        assertEquals(true, context.getContext().get("workerContextAttributeCountryMatchesRoutingCountry"));
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
        task.setTaskRoutingCountryCode("us");
        task.setStatus(TaskStatus.READY);

        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        WorkerMatchContext context = new WorkerMatchContext(worker, null, task, workerManager);

        assertEquals(Map.of(), context.getContext().get("workerContextAttributes"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAllocatable"));
    }
}
