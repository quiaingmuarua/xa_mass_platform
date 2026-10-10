package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import com.xa.mass.workermatching.storage.RedisPlatformLeaseState;
import io.lettuce.core.RedisClient;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;

@Tag("redis-owner")
class PlatformLeaseIntegrationTest {
    @Test void groupResourceCoordinatesAreIndependentAndStaleWritesCannotShortenDeadline() {
        var scope = RedisTestScope.create("partitioned_lease");
        var client = RedisClient.create(REDIS_URL);
        try (var connection = client.connect()) {
            var redis = connection.sync();
            var store = new RedisPlatformLeaseState(() -> redis, scope.keyspace(), Map.of("g", Set.of("p"), "other", Set.of("p")));
            var a = new Coordinate("number", "A", "w");
            var b = new Coordinate("number", "B", "w");
            store.record("g", "p", Map.of(a, 2000L, b, 3000L));
            store.record("g", "p", Map.of(a, 1000L));
            assertThat(store.read("g", "p", List.of(a, b))).containsEntry(a, 2000L).containsEntry(b, 3000L);
            assertThat(store.read("other", "p", List.of(a))).isEmpty();
            assertThat(redis.zcard(RedisPlatformLeaseState.key(scope.keyspace(), "g", "p"))).isEqualTo(2L);
            var reopened = new RedisPlatformLeaseState(() -> redis, scope.keyspace(), Map.of("g", Set.of("p")));
            assertThat(reopened.read("g", "p", List.of(a))).containsEntry(a, 2000L);
            assertThatThrownBy(() -> reopened.record("g", "unknown", Map.of(a, 5000L))).isInstanceOf(IllegalArgumentException.class);
            scope.cleanup(redis);
        } finally { client.shutdown(); }
    }
}
