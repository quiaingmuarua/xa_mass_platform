package com.xa.mass.kernel.task.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime.RpcItemRetentionResult;
import com.xa.mass.kernel.task.TaskRuntime.RpcItemRetentionStage;
import io.lettuce.core.RedisClient;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeRetentionTest {
    @Test void validatesTheEntireRequestBeforeAnyScoreOwnerOrRedisOperation() {
        var reads = new AtomicInteger();
        var client = RedisClient.create("redis://127.0.0.1:1");
        try (var runtime = runtime(client, reads, Map.of())) {
            assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems(null, Map.of(), RpcItemRetentionStage.RELEASE_INPUTS));
            assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems("task", null, RpcItemRetentionStage.RELEASE_INPUTS));
            assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems("task", Map.of(), null));
            var oversized = new LinkedHashMap<String, Long>();
            for (int i = 0; i < 101; i++) oversized.put("item-" + i, 1L);
            assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems("task", oversized, RpcItemRetentionStage.RELEASE_INPUTS));
            for (Long invalid : new Long[]{null, 0L, -1L}) {
                var observed = new LinkedHashMap<String, Long>();
                observed.put("first", 1L);
                observed.put("last", invalid);
                assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems("task", observed, RpcItemRetentionStage.PURGE_TERMINAL));
            }
            assertThrows(IllegalArgumentException.class, () -> runtime.reclaimRpcItems("task", Map.of(" ", 1L), RpcItemRetentionStage.RELEASE_INPUTS));
            assertEquals(0, reads.get());
        } finally { client.shutdown(); }
    }

    @Test void emptyAndUnqualifiedRequestsDoNotConnectToRedisAndUseOneBoundedScoreRead() {
        var reads = new AtomicInteger();
        var client = RedisClient.create("redis://127.0.0.1:1");
        var states = Map.of(
                "active", new TaskItemScoreState(11L, TaskItemScoreBand.ACTIVE, 1, 1000, 1),
                "changed", new TaskItemScoreState(22L, TaskItemScoreBand.TERMINAL, 6, 1000, null));
        try (var runtime = runtime(client, reads, states)) {
            assertEquals(new RpcItemRetentionResult(0, 0, 0, 0, 0),
                    runtime.reclaimRpcItems("task", Map.of(), RpcItemRetentionStage.RELEASE_INPUTS));
            assertEquals(0, reads.get());
            var observed = new LinkedHashMap<String, Long>();
            observed.put("active", 11L);
            observed.put("changed", 21L);
            observed.put("missing", 33L);
            assertEquals(new RpcItemRetentionResult(3, 0, 0, 0, 0),
                    runtime.reclaimRpcItems("task", observed, RpcItemRetentionStage.PURGE_TERMINAL));
            assertEquals(1, reads.get());
        } finally { client.shutdown(); }
    }

    private static RedisTaskRuntime runtime(RedisClient client, AtomicInteger reads, Map<String, TaskItemScoreState> states) {
        var tasks = (TaskScoreBandCore) Proxy.newProxyInstance(TaskScoreBandCore.class.getClassLoader(),
                new Class<?>[]{TaskScoreBandCore.class}, (_p, method, args) -> { throw new AssertionError(method.getName()); });
        var items = (TaskItemScoreBandCore) Proxy.newProxyInstance(TaskItemScoreBandCore.class.getClassLoader(),
                new Class<?>[]{TaskItemScoreBandCore.class}, (_p, method, args) -> {
                    assertEquals("getItemScoreStates", method.getName());
                    assertEquals("task", args[0]);
                    assertEquals(List.of("active", "changed", "missing"), args[1]);
                    reads.incrementAndGet();
                    return states;
                });
        return new RedisTaskRuntime(client, tasks, items, new RedisKeyspace("test_rpc_retention_unit"), 60_000);
    }
}
