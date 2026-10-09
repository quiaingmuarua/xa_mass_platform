package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.index.NetworkEvidenceTimestamps;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class NetworkEvidenceTimestampsIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private MatchingComposition composition;
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach void open() {
        scope = RedisTestScope.create("network_evidence_timestamps");
        client = RedisClient.create(REDIS_URL);
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        });
        connection = client.connect(); redis = connection.sync();
        composition = MatchingComposition.create(client, scope.keyspace(), Map.of(), java.util.List.of(), java.util.List.of());
    }

    @AfterEach void close() {
        if (composition != null) composition.close();
        if (redis != null) scope.cleanup(redis);
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }

    private NetworkEvidenceTimestamps evidence() { return composition.networkEvidenceTimestamps(); }
    private String key(String group) { return NetworkEvidenceTimestamps.key(scope.keyspace(), group); }

    @Test void orderedFilteringRetainsIndependentGroupRecordsWithoutFactsPoolOrScore() {
        var input = new LinkedHashMap<String, Long>();
        input.put("first", 100L); input.put("second", 200L); input.put("third", 300L);
        var accepted = evidence().filterAndAdvance("g:/国", input);
        assertThat(accepted).containsExactly("first", "second", "third");
        assertThatThrownBy(accepted::clear).isInstanceOf(UnsupportedOperationException.class);
        input.put("first", 99L); input.put("second", 200L); input.put("third", 301L);
        assertThat(evidence().filterAndAdvance("g:/国", input)).containsExactly("second", "third");
        assertThat(evidence().filterAndAdvance("other", Map.of("first", 1L))).containsExactly("first");
        assertThat(redis.hgetall(key("g:/国"))).containsExactlyInAnyOrderEntriesOf(
                Map.of("first", "100", "second", "200", "third", "301"));
        assertThat(composition.pools()).isEmpty();
        assertThat(composition.properties().loadWorkerFacts("g:/国", List.of("first"))).containsEntry("first", null);
        assertThat(redis.exists(scope.keyspace().base() + ":worker:score:g:/国",
                scope.keyspace().base() + ":matching:worker:platform-properties:g:/国")).isZero();
        assertThat(redis.ttl(key("g:/国"))).isEqualTo(-1);
        composition.close();
        composition = MatchingComposition.create(client, scope.keyspace(), Map.of(), java.util.List.of(), java.util.List.of());
        assertThat(evidence().filterAndAdvance("g:/国", Map.of("third", 300L))).isEmpty();
    }

    @Test void validatesTheWholeInputAndChunksOnlyAtTheStorageBoundary() {
        evidence().filterAndAdvance("g", Map.of("warm", 1L));
        var input = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 201; i++) input.put("w" + i, 1_000L + i);
        input.put("bad", 0L);
        commands.clear();
        assertThatThrownBy(() -> evidence().filterAndAdvance("g", input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commands).isEmpty();
        input.remove("bad");
        assertThat(evidence().filterAndAdvance("g", input)).containsExactlyElementsOf(input.keySet());
        assertThat(commands).containsExactly("EVAL", "EVAL", "EVAL");
        commands.clear();
        assertThat(evidence().filterAndAdvance("g", Map.of())).isEmpty();
        assertThat(commands).isEmpty();
    }

    @Test void corruptionPreflightPreventsWritesInItsChunkWithoutRepair() {
        for (String invalid : List.of("", "0", "-1", "1.5", "1e3", "NaN", "001", "9223372036854775808")) {
            redis.hset(key("g"), "broken", invalid);
            var input = new LinkedHashMap<String, Long>();
            input.put("fresh", 2L); input.put("broken", 3L);
            assertThatThrownBy(() -> evidence().filterAndAdvance("g", input)).isInstanceOf(RuntimeException.class);
            assertThat(redis.hexists(key("g"), "fresh")).isFalse();
            assertThat(redis.hget(key("g"), "broken")).isEqualTo(invalid);
        }
        redis.set(key("wrong-type"), "keep");
        assertThatThrownBy(() -> evidence().filterAndAdvance("wrong-type", Map.of("w", 2L)))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.get(key("wrong-type"))).isEqualTo("keep");
    }

    @Test void earlierChunksSurviveLaterCorruptionWithoutRetry() {
        evidence().filterAndAdvance("g", Map.of("warm", 1L));
        var input = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 100; i++) input.put("w" + i, 10L);
        input.put("fresh", 10L); input.put("broken", 10L);
        redis.hset(key("g"), "broken", "invalid");
        commands.clear();
        assertThatThrownBy(() -> evidence().filterAndAdvance("g", input)).isInstanceOf(RuntimeException.class);
        assertThat(commands).containsExactly("EVAL", "EVAL");
        assertThat(redis.hget(key("g"), "w99")).isEqualTo("10");
        assertThat(redis.hexists(key("g"), "fresh")).isFalse();
    }

    @Test void comparesIntegralMillisExactlyIncludingBeyondLuaFloatingPointPrecision() {
        long value = 9_007_199_254_740_993L;
        assertThat(evidence().filterAndAdvance("g", Map.of("w", value))).containsExactly("w");
        assertThat(evidence().filterAndAdvance("g", Map.of("w", value - 1))).isEmpty();
        assertThat(evidence().filterAndAdvance("g", Map.of("w", Long.MAX_VALUE))).containsExactly("w");
        assertThat(evidence().filterAndAdvance("g", Map.of("w", Long.MAX_VALUE - 1))).isEmpty();
        assertThat(redis.hget(key("g"), "w")).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test void competingConnectionsNeverMoveTheRecordBackwards() throws Exception {
        try (var other = MatchingComposition.create(client, scope.keyspace(), Map.of(), java.util.List.of(), java.util.List.of());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 1; i <= 50; i++) {
                long time = i;
                var resource = i % 2 == 0 ? evidence() : other.networkEvidenceTimestamps();
                tasks.add(executor.submit(() -> {
                    start.await();
                    resource.filterAndAdvance("g", Map.of("w", time));
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertThat(redis.hget(key("g"), "w")).isEqualTo("50");
        }
    }
}
