package com.xa.mass.kernel.pacer.dispatch;

import static com.xa.mass.kernel.score.redis.WorkerScoreRedisFixture.dueOrdinaryScore;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.*;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real policy and all state Owners: a large logical batch must survive every boundary. */
@Tag("redis-owner")
class AssignmentBatchIntegrationTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"101,worker.any,any", "1000,worker.any,any", "1000,worker.messaging.available,messaging"})
    void oneRoundRefillsQualifiesClaimsAndPublishesTheWholeBatch(int limit, String function, String pool) {
        withOwners((f) -> {
            var ids = IntStream.range(0, limit).mapToObj(i -> "w%04d".formatted(i)).toList();
            f.catalog.registerWorkerGroup(new WorkerGroupDescriptor("g", Map.of(), Set.of("event")));
            for (int offset = 0; offset < ids.size(); offset += 100) {
                var page = ids.subList(offset, Math.min(offset + 100, ids.size()));
                assertThat(f.catalog.registerWorkers("g", page, "adapter")).hasSize(page.size());
                var facts = new LinkedHashMap<String, Map<String, String>>();
                page.forEach(id -> facts.put(id, Map.of("phone", "+" + id, "country", "US", "messaging.enabled", "true")));
                f.matching.properties().upsertWorkerFactsBatch("g", facts);
            }
            long now = System.currentTimeMillis();
            try (var connection = f.client.connect()) {
                var seeded = new LinkedHashMap<String, Double>();
                ids.forEach(id -> seeded.put(id, (double) dueOrdinaryScore(now)));
                connection.sync().zadd(f.scope.keyspace().base() + ":worker:score:g", seeded.entrySet().stream()
                        .map(row -> io.lettuce.core.ScoredValue.just(row.getValue(), row.getKey()))
                        .toArray(io.lettuce.core.ScoredValue[]::new));
            }
            var direct = new LinkedHashMap<String, WorkerQuery>();
            ids.forEach(id -> direct.put(id, new WorkerQuery("worker.messaging.phone", Map.of("phone", "+" + id))));
            f.commands.clear();
            assertThat(f.matching.catalog().take("g", direct)).hasSize(limit);
            assertThat(f.commands).containsExactly("HMGET", "HMGET");

            var task = f.task("live", limit, pool);
            var items = new ArrayList<TaskItem>();
            for (int i = 0; i < limit; i++) items.add(new TaskItem("m" + i, "event", now - 2000,
                    Map.of(), 0, null, new WorkerQuery(function, Map.of())));
            f.appendAndStart(task, items);
            var refill = new WorkerEligibilityRefillPolicy(f.workerScores, f.matching.catalog(), null, limit, System::currentTimeMillis);
            f.commands.clear();
            assertThat(refill.refill(List.of("g"), List.of(task))).isEqualTo(limit);
            // Two bounded head reads and one candidateization Lua, also at 1,000 identities.
            assertThat(Collections.frequency(f.commands, "EVAL")).isEqualTo(3);
            var observed = new ArrayList<WorkerObservation>();
            f.commands.clear();
            assertThat(f.dispatch(limit, observed, System::currentTimeMillis).dispatchTasks(List.of(f.observed(task)))).isEqualTo(limit);
            assertThat(Collections.frequency(f.commands, "EVAL_RO")).isZero();
            assertThat(Collections.frequency(f.commands, "HMGET")).isEqualTo(2); // Items and addresses
            assertThat(observed).hasSize(1);
            assertThat(observed.getFirst().workerIds()).containsExactlyInAnyOrderElementsOf(ids);
            var published = f.delivery.consumeWorkerCommands("adapter", limit);
            assertThat(published).hasSize(limit);
            var correlations = new HashSet<String>();
            var codec = new ResultContextCodec();
            published.forEach((worker, command) -> {
                var context = codec.decodeForRouting(command.forward()).orElseThrow();
                assertThat(context.workerId()).isEqualTo(worker);
                assertThat(context.taskId()).isEqualTo(task.taskId());
                assertThat(context.workerLease()).isNotNull();
                assertThat(correlations.add(context.messageId())).isTrue();
            });
            for (int offset = 0; offset < items.size(); offset += 100) {
                var page = items.subList(offset, Math.min(offset + 100, items.size())).stream().map(TaskItem::messageId).toList();
                assertThat(f.itemScores.getItemScoreStates(task.taskId(), page).values()).allSatisfy(state -> {
                    assertThat(state.tag()).isEqualTo(1);
                    assertThat(state.remainingBudget()).isEqualTo(1);
                });
            }
            assertThat(f.dispatch(limit, observed, System::currentTimeMillis).dispatchTasks(List.of(f.observed(task)))).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 1000})
    void expiredAndExhaustedItemsAllStoreFailureAndLeaveScheduling(int limit) {
        withOwners(f -> {
            long now = System.currentTimeMillis();
            var task = f.task("failed", limit, "any");
            var items = IntStream.range(0, limit).mapToObj(i -> new TaskItem("m" + i, "event", now - 4000,
                    Map.of(), 0, i % 2 == 0 ? now + 10_000 : null, new WorkerQuery("worker.any", Map.of()))).toList();
            f.appendAndStart(task, items);
            var exhausted = new LinkedHashMap<String, Long>();
            f.itemScores.acquireItemScoreCandidates(task.taskId(), limit).forEach((id, score) -> {
                if (Integer.parseInt(id.substring(1)) % 2 != 0) exhausted.put(id, score.score());
            });
            var once = f.itemScores.rewriteObservedItemScores(task.taskId(), exhausted, now - 1500, -1);
            once.forEach((id, result) -> exhausted.put(id, result.score()));
            assertThat(f.itemScores.rewriteObservedItemScores(task.taskId(), exhausted, now - 500, -1).values())
                    .allSatisfy(result -> assertThat(result.status().wireValue()).isEqualTo("transitioned"));
            assertThat(f.dispatch(limit, new ArrayList<>(), () -> now + 20_000).dispatchTasks(List.of(f.observed(task)))).isZero();
            var results = f.tasks.loadTaskItemResults(task.taskId(), items.stream().map(TaskItem::messageId).toList());
            assertThat(results).hasSize(limit);
            assertThat(results.values()).allSatisfy(result -> assertThat(result).isEqualTo(TaskItemResult.failed()));
            assertThat(f.itemScores.observeItemScoreCounts(List.of(task.taskId())).get(task.taskId()).countsByTag())
                    .containsEntry(5, (long) limit).containsEntry(1, 0L);
            assertThat(f.itemScores.acquireItemScoreCandidates(task.taskId(), limit)).isEmpty();
        });
    }

    private static void withOwners(java.util.function.Consumer<Fixture> proof) {
        var scope = RedisTestScope.create("assignment_batch");
        var client = RedisClient.create(REDIS_URL);
        var commands = new CopyOnWriteArrayList<String>();
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commands.add(event.getCommand().getType().toString()); }
        });
        try (var taskScores = new RedisTaskScoreBandCore(client, scope.keyspace());
             var itemScores = new RedisTaskItemScoreBandCore(client, scope.keyspace());
             var tasks = new RedisTaskRuntime(client, taskScores, itemScores, scope.keyspace());
             var workerScores = new RedisWorkerScoreCore(client, scope.keyspace());
             var catalog = new RedisWorkerResourceCatalog(client, workerScores, scope.keyspace());
             var delivery = new RedisWorkerCommandRuntime(client, new com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec(), scope.keyspace());
             var matching = MatchingComposition.create(client, scope.keyspace(), Map.of("g", new MatchingGroup(
                     Set.of("any", "messaging"), Set.of("worker.any", "worker.messaging.phone", "worker.messaging.available"))))) {
            proof.accept(new Fixture(scope, client, taskScores, itemScores, tasks, workerScores, catalog, delivery, matching, commands));
        } finally {
            try (var connection = client.connect()) { scope.cleanup(connection.sync()); }
            finally { client.shutdown(); }
        }
    }

    private record Fixture(RedisTestScope scope, RedisClient client, RedisTaskScoreBandCore taskScores,
            RedisTaskItemScoreBandCore itemScores, RedisTaskRuntime tasks, RedisWorkerScoreCore workerScores,
            RedisWorkerResourceCatalog catalog, RedisWorkerCommandRuntime delivery, MatchingComposition matching,
            List<String> commands) {
        TaskDescriptor task(String id, int limit, String pool) {
            return new TaskDescriptor(id, "test-project", "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                    Map.of("priority", "0", "maxRetryTimes", "1"),
                    List.of(new RefillTarget(pool, new EligibilityQuery(Map.of()), limit)), null, Map.of());
        }
        void appendAndStart(TaskDescriptor task, List<TaskItem> items) {
            assertThat(tasks.createTask(task).status()).isEqualTo(TaskCreationStatus.CREATED);
            assertThat(tasks.appendItems(task.taskId(), items).values()).allSatisfy(result ->
                    assertThat(result.status()).isEqualTo(TaskItemAppendStatus.APPENDED));
            var before = taskScores.getScoreStates(List.of(task.taskId())).get(task.taskId());
            var initial = taskScores.startObservedPreReviewTask(task.taskId(), before.score(), 0);
            taskScores.promoteObservedInitialTasks(Map.of(task.taskId(), initial.score()));
        }
        ObservedTask observed(TaskDescriptor task) {
            return new ObservedTask(task, taskScores.getScoreStates(List.of(task.taskId())).get(task.taskId()).score());
        }
        TaskDispatchPolicy dispatch(int limit, List<WorkerObservation> observations, java.util.function.LongSupplier clock) {
            return new TaskDispatchPolicy(taskScores, itemScores, tasks,
                    new TaskAssignmentDispatcher(itemScores, workerScores, delivery, new ResultContextCodec(), observations::add),
                    new TaskIdleSettlement(taskScores, itemScores), new WorkerCandidateSelectionPolicy(catalog, matching.catalog()),
                    limit, 5, clock);
        }
    }
}
