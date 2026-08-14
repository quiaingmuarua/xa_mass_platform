package com.xa.mass.scenariorpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScenarioRpcEngineTest {

    private final ScenarioRpcEngine engine = ScenarioRpcEngine.create();

    @Test
    void exposesTheSixOrderedBuiltInScenarioTypes() {
        assertEquals(List.of(
                "phonenumber.e164",
                "phonenumber.country",
                "phonenumber.original-carrier",
                "string.md5",
                "string.sha1",
                "string.base64.encode"
        ), engine.scenarioTypes().stream()
                .map(ScenarioRpcDescriptor::scenarioType)
                .toList());
        assertTrue(engine.scenarioTypes().stream().allMatch(
                descriptor -> descriptor.scenarioType()
                        .equals(descriptor.eventCode())
        ));
    }

    @Test
    void appendsOncePollsOnlyPendingAndRestoresInputOrder() {
        ScenarioRpcScenario scenario = engine.createScenario("string.md5");
        RecordingExchange exchange = new RecordingExchange();
        List<List<String>> sinkRounds = new ArrayList<>();

        ScenarioRpcRunOutcome outcome = scenario.run(
                "scenario-1000",
                List.of("first", "second", "third"),
                new ScenarioRpcPollingPolicy(1, 3),
                exchange,
                results -> sinkRounds.add(results.stream()
                        .map(result -> (String) result.input().get("value"))
                        .toList())
        );

        assertEquals(1, exchange.appendCalls.get());
        assertEquals(3, exchange.appended.size());
        assertEquals(List.of(3, 1), exchange.loadSizes);
        assertEquals(List.of(
                List.of("first", "third"),
                List.of("second")
        ), sinkRounds);
        assertEquals(ScenarioRpcRunStatus.SUCCEEDED, outcome.status());
        assertEquals(0, outcome.remainingCount());
        assertEquals(2, outcome.loadRounds());
        assertEquals(List.of("first", "second", "third"), outcome.results()
                .stream()
                .map(result -> (String) result.input().get("value"))
                .toList());
        assertTrue(outcome.results().stream().allMatch(
                result -> result.messageId().matches(
                        "scenario-1000-string\\.md5-[0-9a-f]{8}"
                )
        ));
    }

    @Test
    void parsesEveryLineBeforeAppendAndEmptyInputDoesNoExchange() {
        AtomicInteger appends = new AtomicInteger();
        ScenarioRpcBatchExchange exchange = new ScenarioRpcBatchExchange() {
            @Override
            public void append(
                    ScenarioRpcDescriptor descriptor,
                    List<ScenarioRpcItem> items
            ) {
                appends.incrementAndGet();
            }

            @Override
            public Map<String, Map<String, Object>> loadResults(
                    ScenarioRpcDescriptor descriptor,
                    List<String> pendingMessageIds
            ) {
                return Map.of();
            }
        };

        assertThrows(IllegalArgumentException.class, () ->
                engine.createScenario("phonenumber.e164").run(
                        "scenario-1",
                        List.of("+8613800138000", " "),
                        new ScenarioRpcPollingPolicy(1, 1),
                        exchange,
                        ignored -> {
                        }
                )
        );
        assertEquals(0, appends.get());

        ScenarioRpcRunOutcome empty = engine.createScenario("string.md5")
                .run(
                        "scenario-2",
                        List.of(),
                        new ScenarioRpcPollingPolicy(1, 1),
                        exchange,
                        ignored -> {
                        }
                );
        assertEquals(0, appends.get());
        assertEquals(ScenarioRpcRunStatus.SUCCEEDED, empty.status());
        assertEquals(0, empty.loadRounds());
    }

    @Test
    void returnsPartialAfterConfiguredLoadRounds() {
        ScenarioRpcBatchExchange exchange = new EmptyExchange();

        ScenarioRpcRunOutcome outcome = engine.createScenario("string.sha1")
                .run(
                        "scenario-3",
                        List.of("a", "b"),
                        new ScenarioRpcPollingPolicy(1, 2),
                        exchange,
                        ignored -> {
                        }
                );

        assertEquals(ScenarioRpcRunStatus.PARTIAL, outcome.status());
        assertEquals(2, outcome.remainingCount());
        assertEquals(2, outcome.loadRounds());
        assertEquals(List.of(), outcome.results());
    }

    @Test
    void rejectsInvalidOrUnexpectedResultsBeforeSink() {
        AtomicInteger sinkCalls = new AtomicInteger();
        ScenarioRpcBatchExchange invalid = exchangeReturning(
                Map.of("valid", true)
        );
        assertThrows(IllegalStateException.class, () ->
                engine.createScenario("string.md5").run(
                        "scenario-4",
                        List.of("a"),
                        new ScenarioRpcPollingPolicy(1, 1),
                        invalid,
                        ignored -> sinkCalls.incrementAndGet()
                )
        );
        assertEquals(0, sinkCalls.get());

        ScenarioRpcBatchExchange unexpected = new ScenarioRpcBatchExchange() {
            @Override
            public void append(
                    ScenarioRpcDescriptor descriptor,
                    List<ScenarioRpcItem> items
            ) {
            }

            @Override
            public Map<String, Map<String, Object>> loadResults(
                    ScenarioRpcDescriptor descriptor,
                    List<String> pendingMessageIds
            ) {
                return Map.of(
                        "unexpected",
                        Map.of("valid", true, "md5", "hash")
                );
            }
        };
        assertThrows(IllegalStateException.class, () ->
                engine.createScenario("string.md5").run(
                        "scenario-5",
                        List.of("a"),
                        new ScenarioRpcPollingPolicy(1, 1),
                        unexpected,
                        ignored -> {
                        }
                )
        );
    }

    @Test
    void rejectsUnknownScenarioAndUnboundedPolling() {
        assertThrows(IllegalArgumentException.class, () ->
                engine.createScenario("unknown")
        );
        assertThrows(IllegalArgumentException.class, () ->
                new ScenarioRpcPollingPolicy(0, 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
                new ScenarioRpcPollingPolicy(1_001, 301)
        );
    }

    private static ScenarioRpcBatchExchange exchangeReturning(
            Map<String, Object> result
    ) {
        return new ScenarioRpcBatchExchange() {
            private String messageId;

            @Override
            public void append(
                    ScenarioRpcDescriptor descriptor,
                    List<ScenarioRpcItem> items
            ) {
                messageId = items.getFirst().messageId();
            }

            @Override
            public Map<String, Map<String, Object>> loadResults(
                    ScenarioRpcDescriptor descriptor,
                    List<String> pendingMessageIds
            ) {
                return Map.of(messageId, result);
            }
        };
    }

    private static final class EmptyExchange
            implements ScenarioRpcBatchExchange {
        @Override
        public void append(
                ScenarioRpcDescriptor descriptor,
                List<ScenarioRpcItem> items
        ) {
        }

        @Override
        public Map<String, Map<String, Object>> loadResults(
                ScenarioRpcDescriptor descriptor,
                List<String> pendingMessageIds
        ) {
            return Map.of();
        }
    }

    private static final class RecordingExchange
            implements ScenarioRpcBatchExchange {
        private final AtomicInteger appendCalls = new AtomicInteger();
        private final List<Integer> loadSizes = new ArrayList<>();
        private List<ScenarioRpcItem> appended = List.of();
        private int round;

        @Override
        public void append(
                ScenarioRpcDescriptor descriptor,
                List<ScenarioRpcItem> items
        ) {
            appendCalls.incrementAndGet();
            appended = List.copyOf(items);
        }

        @Override
        public Map<String, Map<String, Object>> loadResults(
                ScenarioRpcDescriptor descriptor,
                List<String> pendingMessageIds
        ) {
            loadSizes.add(pendingMessageIds.size());
            Map<String, Map<String, Object>> results = new LinkedHashMap<>();
            if (round++ == 0) {
                results.put(
                        appended.get(2).messageId(),
                        result("third")
                );
                results.put(
                        appended.get(0).messageId(),
                        result("first")
                );
            } else {
                results.put(
                        appended.get(1).messageId(),
                        result("second")
                );
            }
            return results;
        }

        private static Map<String, Object> result(String value) {
            return Map.of("valid", true, "md5", "hash-" + value);
        }
    }
}
