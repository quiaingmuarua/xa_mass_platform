package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkerSelectorAdmissionTest {
    @Test
    void matchingOwnsBindingParametersAndGroupAdmissionWithoutRedisAccess() {
        // An unreachable target proves that validation performs no lookup or selection.
        RedisClient client = RedisClient.create("redis://127.0.0.1:1");
        try (var catalog = new RedisWorkerMatchingCatalog(client, new RedisKeyspace("test_selector_admission"), Map.of("g",Set.of("worker.country")))) {
            assertDoesNotThrow(() -> RuleIndex.query(() -> { throw new AssertionError("unexpected Redis read"); },"index",RuleHandler.COUNTRY::criteria));
            assertDoesNotThrow(() -> RuleHandler.COUNTRY.criteria(
                    TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("CN"))))));
            for (Map<?, ?> expression : List.of(
                    Map.of("worker.test.region", List.of("east", "west")),
                    Map.of("country", List.of("CN")),
                    Map.of("worker.country", Map.of("op", "range", "values", List.of("CN", "US"))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of("$eq", "CN"))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of(""))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of("cn"))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of(" CN"))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of("中国"))),
                    Map.of("worker.country", Map.of("op", "in", "values", List.of("ABC"))),
                    Map.of("worker.country", List.of("CN")))) {
                var selector = TaskItemWorkerSelector.parse(expression);
                assertThrows(IllegalArgumentException.class,
                        () -> RuleHandler.COUNTRY.criteria( selector), expression.toString());
                assertThrows(IllegalArgumentException.class, () -> RuleIndex.query(() -> { throw new AssertionError("unexpected Redis read"); },"index",RuleHandler.COUNTRY::criteria).take(Map.of(selector, 1)));
                assertThrows(IllegalArgumentException.class,
                        () -> RuleIndex.query(() -> { throw new AssertionError("unexpected Redis read"); },"index",RuleHandler.COUNTRY::criteria).retain(Map.of(selector, List.of("worker"))));
            }
        } finally {
            client.shutdown();
        }
    }
}
