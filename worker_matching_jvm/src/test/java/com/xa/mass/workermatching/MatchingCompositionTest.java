package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.index.IndexMutation;
import com.xa.mass.workermatching.index.PhoneIndex;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MatchingCompositionTest {
    private final RedisKeyspace keyspace = new RedisKeyspace("test_matching_composition");

    @Test void directOnlyCompositionHasNoPoolOrPolicyAndAdmissionNeedsNoConnection() {
        var client = mock(RedisClient.class);
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone")));
        try (var store = new FactsIndexStore(client, keyspace, MatchingComposition.indexes(groups))) {
            var composition = new MatchingComposition(store, groups, () -> 1_000L);
            assertTrue(composition.pools().isEmpty());
            assertTrue(composition.policies().isEmpty());
            assertEquals(Set.of("workerId", "worker.phone"), composition.functions().keySet());
            try (var catalog = composition.catalog()) {
                assertEquals(List.of(), catalog.normalizeRefill("g", List.of()));
                assertEquals(Set.of(), catalog.groupsNeedingRefill(Map.of()));
                var query = new WorkerQuery("worker.phone", "+1");
                assertEquals(query, catalog.normalizeQuery("g", query));
                assertEquals("w", catalog.take("g", Map.of("m", new WorkerQuery("workerId", "w"))).get("m").workerId());
                assertEquals(10_000, composition.budget().available());
                verifyNoInteractions(client);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test void allEnabledIndexesShareOneConnectionAndCatalogCloseDoesNotOwnClient() {
        var client = mock(RedisClient.class);
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var commands = (RedisCommands<String, String>) mock(RedisCommands.class);
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        var keys = new KeyScanCursor<String>(); keys.setCursor("0"); keys.setFinished(true);
        var facts = new MapScanCursor<String, String>(); facts.setCursor("0"); facts.setFinished(true);
        when(commands.scan(any(ScanCursor.class), any(ScanArgs.class))).thenReturn(keys);
        when(commands.hscan(anyString(), any(ScanCursor.class), any(ScanArgs.class))).thenReturn(facts);
        var groups = Map.of("g", new MatchingGroup(Set.of("messaging", "proof-facts"), Set.of("worker.phone")));
        var catalog = MatchingComposition.create(client, keyspace, groups);
        // Rebuild visits all three resources, but acquires only one physical connection.
        verify(commands, times(3)).unlink(anyString());
        verify(client, times(1)).connect(StringCodec.UTF8);
        catalog.close(); catalog.close();
        verify(connection, times(1)).close();
        verify(client, never()).shutdown();
    }

    @SuppressWarnings("unchecked")
    @Test void startupRebuildFailureClosesTheCreatedConnectionAndPreservesOriginalFailure() {
        var client = mock(RedisClient.class);
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var commands = (RedisCommands<String, String>) mock(RedisCommands.class);
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        var failure = new IllegalStateException("rebuild failed");
        when(commands.unlink(anyString())).thenThrow(failure);
        var closeFailure = new IllegalStateException("close failed");
        doThrow(closeFailure).when(connection).close();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone"))))));
        assertArrayEquals(new Throwable[]{closeFailure}, failure.getSuppressed());
        verify(connection, times(1)).close();
        verify(client, never()).shutdown();
    }

    @Test void partialAssemblyFailureDoesNotOpenAConnection() {
        var client = mock(RedisClient.class);
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of("any"), Set.of("worker.phone", "unknown")))));
        verifyNoInteractions(client);
    }

    @Test void conflictingIndexDefinitionsFailBeforeOpeningAConnection() {
        var client = mock(RedisClient.class);
        var phone = PhoneIndex.mutation();
        assertThrows(IllegalArgumentException.class, () -> new FactsIndexStore(client, keyspace,
                Map.of("g", List.of(phone, new IndexMutation(phone.namespace(), "different definition")))));
        verifyNoInteractions(client);
    }
}
