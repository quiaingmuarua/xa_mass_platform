package com.xa.mass.workermatching.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RedisHashPropertyIndexTest {
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    private final RedisKeyspace keyspace = new RedisKeyspace("test_property_index");
    private final PropertyIndex index = new RedisHashPropertyIndex(() -> commands, keyspace, "phone");

    @Test void validatesTheWholeBatchBeforeReadingAndEmptyIsFree() {
        assertEquals(Map.of(), index.lookup("g", List.of()));
        for (var invalid : List.of(List.of("ok", ""), List.of("same", "same"),
                Arrays.asList("ok", null), IntStream.range(0, 101).mapToObj(Integer::toString).toList()))
            assertThrows(IllegalArgumentException.class, () -> index.lookup("g", invalid));
        verifyNoInteractions(commands);
    }

    @Test void preservesExactValuesAndRequestOrderWithOneHmget() {
        String key = RedisHashPropertyIndex.key(keyspace, "g", "phone");
        when(commands.hmget(key, " +1 ", "missing", "+1")).thenReturn(List.of(
                KeyValue.just(" +1 ", "a"), KeyValue.empty("missing"), KeyValue.just("+1", "b")));
        var result = index.lookup("g", List.of(" +1 ", "missing", "+1"));
        assertEquals(List.of(" +1 ", "+1"), List.copyOf(result.keySet()));
        assertEquals(Map.of(" +1 ", "a", "+1", "b"), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
        verify(commands).hmget(key, " +1 ", "missing", "+1");
        verifyNoMoreInteractions(commands);
    }

    @Test void maximumBatchAndArbitraryPropertyUseTheSameBoundedRead() {
        var values = IntStream.range(0, 100).mapToObj(Integer::toString).toList();
        String key = RedisHashPropertyIndex.key(keyspace, "g:*[x]", "account:id");
        when(commands.hmget(key, values.toArray(String[]::new)))
                .thenReturn(values.stream().map(v -> KeyValue.just(v, "w" + v)).toList());
        var account = new RedisHashPropertyIndex(() -> commands, keyspace, "account:id");
        assertEquals(100, account.lookup("g:*[x]", values).size());
        assertNotEquals(key, RedisHashPropertyIndex.key(keyspace, "g:*[x]:account", "id"));
        assertNotEquals(key, RedisHashPropertyIndex.key(keyspace, "g:*[x]", "phone"));
        verify(commands).hmget(key, values.toArray(String[]::new));
        verifyNoMoreInteractions(commands);
    }
}
