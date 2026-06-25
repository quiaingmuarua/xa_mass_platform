package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.contract.WorkerScoreBandSlotRuntimeContractTest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisWorkerScoreBandSlotRuntimeTest extends WorkerScoreBandSlotRuntimeContractTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> commands;
    private RedisWorkerScoreBandSlotRuntime runtime;
    private RedisWorkerScoreBandSlotKeyspace keyspace;

    @Override
    protected WorkerScoreBandSlotRuntime createRuntime() {
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("worker score-band slot runtime contract test");
        observerConnection = redisClient.connect();
        commands = observerConnection.sync();
        keyspace = new RedisWorkerScoreBandSlotKeyspace(
                RedisRuntimeTestSupport.namespace("worker-score-band")
        );
        runtime = new RedisWorkerScoreBandSlotRuntime(redisClient, keyspace, false);
        return runtime;
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (commands != null && keyspace != null) {
            RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace.namespace());
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        commands = null;
        keyspace = null;
        observerConnection = null;
        redisClient = null;
    }

    @Test
    void storesOnlyScoreAndMetadataKeysForWorkerSlots() {
        RedisWorkerScoreBandSlotRuntime scoreBandRuntime = (RedisWorkerScoreBandSlotRuntime) createRuntime();
        WorkerScoreBandSlotMetadata metadata = WorkerScoreBandSlotMetadata.worker(
                "group-a",
                "worker-1",
                "mailbox-a",
                Map.of("region", "us"),
                2
        );

        scoreBandRuntime.upsert(metadata, WorkerScoreBand.eligibleScore(NOW), "register", NOW);

        assertEquals(List.of("worker-1"), commands.zrange(keyspace.scoreZset("group-a"), 0, -1));
        assertNotNull(commands.hget(keyspace.metadataHash("group-a"), "worker-1"));
        List<String> keys = commands.keys(keyspace.namespace() + ":*");
        assertTrue(keys.contains(keyspace.scoreZset("group-a")));
        assertTrue(keys.contains(keyspace.metadataHash("group-a")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":hold:")));
    }
}
