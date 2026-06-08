package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCandidateWarmPoolTest {

    @Test
    void globalAndTtlEvictionRemoveEmptyTaskBuckets() {
        TaskCandidateWarmPool pool = new TaskCandidateWarmPool(1, 2, 100L);

        TaskCandidateWarmPool.Entry first = entry("task-1", "worker-1", 1L);
        TaskCandidateWarmPool.Entry second = entry("task-2", "worker-2", 2L);
        TaskCandidateWarmPool.Entry third = entry("task-3", "worker-3", 3L);

        pool.put(first);
        pool.put(second);
        pool.put(third);

        assertTrue(pool.sample("task-1", 50L, 1).isEmpty());
        assertEquals(0, pool.sizeForTask("task-1"));
        assertEquals(2, pool.trackedTaskCount());

        pool.remove(second);

        assertEquals(0, pool.sizeForTask("task-2"));
        assertEquals(1, pool.trackedTaskCount());

        assertEquals(List.of(), pool.sample("task-3", 200L, 1));
        assertEquals(0, pool.sizeForTask("task-3"));
        assertEquals(0, pool.trackedTaskCount());
    }

    private static TaskCandidateWarmPool.Entry entry(String taskId, String workerId, long observedAtMillis) {
        return new TaskCandidateWarmPool.Entry(
                taskId,
                workerId,
                "group-a",
                null,
                WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY,
                observedAtMillis
        );
    }
}
