package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.MatchingGroup.AssignmentWindow;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class AssignmentWindowIntegrationTest {
    @Test void realSnapshotFiltersWithoutMutatingFactsAndFailuresDoNotRestoreStock() {
        var scope = RedisTestScope.create("assignment_window");
        var commands = new CopyOnWriteArrayList<String>();
        var client = RedisClient.create(REDIS_URL);
        client.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commands.add(event.getCommand().getType().toString()); }
        });
        var clock = new AtomicLong(120_000);
        var groups = Map.of("g", new MatchingGroup(Set.of("any"), Set.of("worker.assignment.available", "worker.any"),
                new AssignmentWindow(60_000, 10)));
        try (var witness = client.connect();
             var composition = new MatchingComposition(new FactsIndexStore(client, scope.keyspace(), Map.of()), groups, clock::get)) {
            try {
                var properties = composition.properties();
                properties.upsertWorkerFactsBatch("g", Map.of("blocked", Map.of(), "new", Map.of(), "bad", Map.of()));
                properties.patchWorkerPlatformProperties("g", "blocked", Map.of("lastAssignedAt", 120_000, "windowAssignmentCount", 10, "keep", "value"));
                var saved = properties.loadWorkerFacts("g", List.of("blocked"));
                var pool = composition.pools().get("any");
                pool.offerBatch("g", "any", List.of(new WorkerCandidate("blocked", 11), new WorkerCandidate("new", 12)));
                var requests = new LinkedHashMap<String, WorkerQuery>();
                requests.put("first", new WorkerQuery("worker.assignment.available", Map.of()));
                requests.put("second", new WorkerQuery("worker.assignment.available", Map.of()));
                commands.clear();
                assertThat(composition.catalog().take("g", requests)).containsExactlyEntriesOf(Map.of("first", new WorkerCandidate("new", 12)));
                assertThat(commands).containsExactly("EVAL_RO");
                commands.clear();
                assertThat(composition.catalog().take("g", requests)).isEmpty();
                assertThat(commands).isEmpty();
                clock.set(180_000);
                pool.offerBatch("g", "any", List.of(new WorkerCandidate("blocked", 13)));
                assertThat(composition.catalog().take("g", requests)).containsEntry("first", new WorkerCandidate("blocked", 13));
                assertThat(properties.loadWorkerFacts("g", List.of("blocked"))).isEqualTo(saved);

                String key = scope.keyspace().base() + ":matching:worker:platform-properties:g";
                witness.sync().hset(key, "bad", "malformed-json");
                pool.offerBatch("g", "any", List.of(new WorkerCandidate("bad", 14)));
                assertThatThrownBy(() -> composition.catalog().take("g", requests)).isInstanceOf(IllegalArgumentException.class);
                assertThat(pool.countByKey("g")).isEmpty();
                assertThat(witness.sync().hget(key, "bad")).isEqualTo("malformed-json");
                pool.offerBatch("g", "any", List.of(new WorkerCandidate("bad", 15)));
                assertThat(composition.catalog().take("g", Map.of("legacy", new WorkerQuery("worker.any", Map.of()))))
                        .containsEntry("legacy", new WorkerCandidate("bad", 15));
            } finally { scope.cleanup(witness.sync()); }
        } finally { client.shutdown(); }
    }
}
