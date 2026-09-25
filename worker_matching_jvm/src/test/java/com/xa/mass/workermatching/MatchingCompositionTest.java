package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
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
        try (var store = new FactsIndexStore(client, keyspace, MatchingComposition.indexedProperties(groups))) {
            var composition = new MatchingComposition(store, groups, () -> 1_000L);
            assertTrue(composition.pools().isEmpty());
            assertTrue(composition.policies().isEmpty());
            assertEquals(Set.of("workerId", "worker.phone"), composition.functions().keySet());
            {
            var catalog = composition.catalog();
                assertEquals(List.of(), catalog.normalizeRefill("g", List.of()));
                assertEquals(Map.of(), catalog.observeRefillDeficits(Map.of()));
                var query = new WorkerQuery("worker.phone", "+1");
                assertEquals(query, catalog.normalizeQuery("g", query));
                assertEquals("w", catalog.take("g", Map.of("m", new WorkerQuery("workerId", "w"))).get("m").workerId());
                assertEquals(10_000, composition.budget().available());
                verifyNoInteractions(client);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test void propertiesAndIndexesShareOneConnectionAndCompositionDoesNotOwnClient() {
        var client = mock(RedisClient.class);
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        var commands = (RedisCommands<String, String>) mock(RedisCommands.class);
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.hmget(anyString(), eq("number"))).thenReturn(List.of(KeyValue.just("number", "w")));
        when(commands.hmget(anyString(), eq("w"))).thenReturn(List.of(KeyValue.just("w", "{}")));
        var groups = Map.of("g", new MatchingGroup(Set.of("messaging", "proof-facts"), Set.of("worker.phone", "worker.messaging.phone")));
        var composition = MatchingComposition.create(client, keyspace, groups);
        var catalog = composition.catalog();
        assertSame(catalog, composition.catalog());
        assertSame(composition.properties(), composition.properties());
        verifyNoInteractions(client);
        catalog.take("g", Map.of("m", new WorkerQuery("worker.phone", "number")));
        catalog.take("g", Map.of("n", new WorkerQuery("worker.phone", "number")));
        assertEquals("w", composition.properties().loadWorkerFacts("g", List.of("w")).get("w").workerId());
        verify(commands, times(2)).hmget(anyString(), eq("number"));
        verify(commands, times(2)).hmget(anyString(), eq("w"));
        verifyNoMoreInteractions(commands);
        verify(client, times(1)).connect(StringCodec.UTF8);
        composition.close(); composition.close();
        verify(connection, times(1)).close();
        verify(client, never()).shutdown();
    }

    @Test void startupAndCloseDoNotOpenRedisOrRebuildIndexes() {
        var client = mock(RedisClient.class);
        try (var composition = MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.phone"))))) {
            assertEquals(Map.of(), composition.catalog().take("g", Map.of()));
        }
        verifyNoInteractions(client);
    }

    @Test void partialAssemblyFailureDoesNotOpenAConnection() {
        var client = mock(RedisClient.class);
        assertThrows(IllegalArgumentException.class, () -> MatchingComposition.create(client, keyspace,
                Map.of("g", new MatchingGroup(Set.of("any"), Set.of("worker.phone", "unknown")))));
        verifyNoInteractions(client);
    }

    @Test void invalidPropertyConfigurationFailsBeforeOpeningAConnection() {
        var client = mock(RedisClient.class);
        assertThrows(IllegalArgumentException.class, () -> new FactsIndexStore(client, keyspace,
                Map.of("g", Set.of(" "))));
        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    @Test void failedAssemblyClosesAnAlreadyOpenedStoreWithoutClosingTheClient() {
        var client = mock(RedisClient.class);
        var connection = (StatefulRedisConnection<String, String>) mock(StatefulRedisConnection.class);
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        var store = new FactsIndexStore(client, keyspace, Map.of("unavailable", Set.of("phone")));
        store.commands();
        assertThrows(IllegalArgumentException.class, () -> new MatchingComposition(store, Map.of(), () -> 0));
        store.close();
        verify(connection, times(1)).close();
        verify(client, never()).shutdown();
        assertThrows(IllegalStateException.class, store::commands);
    }

    @Test void qualifiedPhoneNeedsOnlyOnePhoneIndexAndNoPoolOrGenericPhoneFunction() {
        var client = mock(RedisClient.class);
        var groups = Map.of("g", new MatchingGroup(Set.of(), Set.of("worker.messaging.phone")));
        var indexes = MatchingComposition.indexedProperties(groups);
        assertEquals(Set.of("phone"), indexes.get("g"));
        assertThrows(UnsupportedOperationException.class, indexes::clear);
        try (var store = new FactsIndexStore(client, keyspace, indexes)) {
            var composition = new MatchingComposition(store, groups, () -> 1_000L);
            assertTrue(composition.pools().isEmpty());
            assertTrue(composition.policies().isEmpty());
            assertEquals(Set.of("workerId", "worker.messaging.phone"), composition.functions().keySet());
            {
            var catalog = composition.catalog();
                var query = new WorkerQuery("worker.messaging.phone", Map.of("phone", "+1"));
                assertEquals(query, catalog.normalizeQuery("g", query));
                assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("other", query));
                assertThrows(IllegalArgumentException.class, () -> catalog.normalizeQuery("g", new WorkerQuery("worker.phone", "+1")));
                assertEquals(List.of(), catalog.normalizeRefill("g", List.of()));
                verifyNoInteractions(client);
            }
        }
        assertEquals(1, MatchingComposition.indexedProperties(Map.of("g", new MatchingGroup(Set.of(),
                Set.of("worker.phone", "worker.messaging.phone")))).get("g").size());
    }
}
