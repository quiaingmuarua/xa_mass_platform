package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("redis-owner")
class RedisWorkerCommandAppendIntegrationTest {
    private RedisTestScope scope;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerCommandRuntime owner;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @BeforeEach void start() {
        scope = RedisTestScope.create("command_append");
        client = RedisClient.create(REDIS_URL);
        connection = client.connect(StringCodec.UTF8);
        redis = connection.sync();
        owner = new RedisWorkerCommandRuntime(client, codec, scope.keyspace());
        owner.consumeWorkerCommand("warmup", "absent");
    }

    @AfterEach void stop() {
        owner.close();
        scope.cleanup(redis);
        connection.close();
        client.shutdown();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 100, 101})
    void currentAppendCostIsOneTimePlusOneInsertAttemptPerWorker(int count) {
        var input = commands(count);
        var calls = new CopyOnWriteArrayList<String>();
        var listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                calls.add(event.getCommand().getType().toString());
            }
        };
        client.addListener(listener);
        try {
            owner.close();
            owner = new RedisWorkerCommandRuntime(client, codec, scope.keyspace());
            owner.consumeWorkerCommand("warmup", "absent");
            calls.clear();
            var result = owner.appendWorkerCommands("adapter", input);
            assertThat(result.keySet()).containsExactlyElementsOf(input.keySet());
            if (count == 0) assertThat(result).isEmpty();
            else assertThat(result.values()).containsOnly(WorkerCommandAppendStatus.APPENDED);
            var expected = new ArrayList<String>();
            if (count > 0) expected.add("TIME");
            for (int offset = 0; offset < count; offset++) expected.add("HSETNX");
            assertThat(calls).containsExactlyElementsOf(expected);
        } finally {
            client.removeListener(listener);
        }
        assertThat(redis.hlen(key())).isEqualTo(count);
        input.forEach((worker, command) -> assertThat(owner.consumeWorkerCommand("adapter", worker)).isEqualTo(command));
    }

    @Test void mixedSlotsPreserveTaskAuthorityAndAdapterIsolation() {
        var direct = command(DeliveryEndpoint.SERVER, "direct");
        var task = command(DeliveryEndpoint.TASK, "task");
        assertThat(owner.offerWorkerCommands("adapter", Map.of("w0", direct)))
                .containsEntry("w0", WorkerCommandOfferStatus.OFFERED);
        owner.appendWorkerCommands("other", Map.of("w0", direct));
        var input = new LinkedHashMap<String, DeliveryCommand>();
        input.put("w0", task);
        input.put("w1", task);
        assertThat(owner.appendWorkerCommands("adapter", input)).containsExactly(
                Map.entry("w0", WorkerCommandAppendStatus.REPLACED),
                Map.entry("w1", WorkerCommandAppendStatus.APPENDED));
        assertThat(owner.offerWorkerCommands("adapter", Map.of("w0", direct)))
                .containsEntry("w0", WorkerCommandOfferStatus.OCCUPIED);
        assertThat(owner.consumeWorkerCommand("adapter", "w0")).isEqualTo(task);
        assertThat(owner.consumeWorkerCommand("adapter", "w1")).isEqualTo(task);
        assertThat(owner.consumeWorkerCommand("other", "w0")).isEqualTo(direct);
    }

    @Test void occupiedBatchReportsEveryReplacementAndStoresEveryNewCommand() {
        owner.appendWorkerCommands("adapter", commands(100));
        var replacements = new LinkedHashMap<String, DeliveryCommand>();
        for (int i = 0; i < 100; i++) replacements.put("w" + i, command(DeliveryEndpoint.TASK, "new-" + i));
        assertThat(owner.appendWorkerCommands("adapter", replacements).values())
                .containsOnly(WorkerCommandAppendStatus.REPLACED);
        assertThat(owner.consumeWorkerCommands("adapter", 100)).isEqualTo(replacements);
    }

    @Test void invalidLastMemberCannotWriteEarlierCommands() {
        var input = commands(101);
        input.put("w100", null);
        assertThatThrownBy(() -> owner.appendWorkerCommands("adapter", input))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.exists(key())).isZero();
        input.put("w100", DeliveryCommand.create(DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER, "test.event", 1, "{}", "expired"));
        assertThatThrownBy(() -> owner.appendWorkerCommands("adapter", input))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.exists(key())).isZero();
    }

    @Test void encodingFailureCannotWriteEarlierCommands() {
        WorkerDeliveryCodec failing = mock(WorkerDeliveryCodec.class, delegatesTo(codec));
        var input = commands(101);
        var last = input.get("w100");
        when(failing.encodeDeliveryCommand(last)).thenThrow(new IllegalArgumentException("encoding failure"));
        try (var tested = new RedisWorkerCommandRuntime(client, failing, scope.keyspace())) {
            assertThatThrownBy(() -> tested.appendWorkerCommands("adapter", input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(redis.exists(key())).isZero();
    }

    @Test void replacementBetweenObservationAndConsumptionSurvives() {
        var old = command(DeliveryEndpoint.TASK, "old");
        var replacement = command(DeliveryEndpoint.TASK, "replacement");
        owner.appendWorkerCommands("adapter", Map.of("w0", old));
        var intercepted = intercept(connection);
        doAnswer(invocation -> {
            var observed = redis.hrandfieldWithvalues(key(), 100);
            owner.appendWorkerCommands("adapter", Map.of("w0", replacement));
            return observed;
        }).when(intercepted).hrandfieldWithvalues(key(), 100);
        try (var tested = interceptedOwner(intercepted)) {
            assertThat(tested.consumeWorkerCommands("adapter", 100)).isEmpty();
        }
        assertThat(owner.consumeWorkerCommand("adapter", "w0")).isEqualTo(replacement);
    }

    @Test void closedConnectionAfterOneHundredWritesLeavesEarlierCommandsApplied() {
        try (var failingConnection = client.connect(StringCodec.UTF8)) {
            var intercepted = intercept(failingConnection);
            var writes = new AtomicInteger();
            doAnswer(invocation -> {
                if (writes.incrementAndGet() == 101) failingConnection.close();
                try {
                    return invocation.getMethod().invoke(failingConnection.sync(), invocation.getRawArguments());
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw error.getCause();
                }
            }).when(intercepted).hsetnx(anyString(), anyString(), anyString());
            try (var tested = interceptedOwner(intercepted)) {
                assertThatThrownBy(() -> tested.appendWorkerCommands("adapter", commands(101)))
                        .isInstanceOf(RuntimeException.class);
            }
        }
        assertThat(redis.hkeys(key())).containsExactlyInAnyOrderElementsOf(commands(100).keySet());
    }

    @SuppressWarnings("unchecked")
    private RedisCommands<String, String> intercept(StatefulRedisConnection<String, String> actual) {
        return mock(RedisCommands.class, delegatesTo(actual.sync()));
    }

    @SuppressWarnings("unchecked")
    private RedisWorkerCommandRuntime interceptedOwner(RedisCommands<String, String> commands) {
        var fakeClient = mock(RedisClient.class);
        var fakeConnection = mock(StatefulRedisConnection.class);
        when(fakeClient.connect(StringCodec.UTF8)).thenReturn(fakeConnection);
        when(fakeConnection.sync()).thenReturn(commands);
        return new RedisWorkerCommandRuntime(fakeClient, codec, scope.keyspace());
    }

    private LinkedHashMap<String, DeliveryCommand> commands(int count) {
        var result = new LinkedHashMap<String, DeliveryCommand>();
        for (int i = 0; i < count; i++) result.put("w" + i, command(DeliveryEndpoint.TASK, "m" + i));
        return result;
    }

    private DeliveryCommand command(DeliveryEndpoint src, String token) {
        return DeliveryCommand.create(src, DeliveryEndpoint.WORKER, "test.event",
                System.currentTimeMillis() + 60_000, "{}", token);
    }

    private String key() { return scope.keyspace().base() + ":delivery:commands:adapter"; }
}
