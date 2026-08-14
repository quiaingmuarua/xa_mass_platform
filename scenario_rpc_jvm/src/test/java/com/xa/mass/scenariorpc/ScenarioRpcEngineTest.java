package com.xa.mass.scenariorpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScenarioRpcEngineTest {

    private final ScenarioRpcEngine engine = ScenarioRpcEngine.create();

    @Test
    void exposesTheSixOrderedBuiltInScenarios() {
        assertEquals(List.of(
                "phonenumber.e164",
                "phonenumber.country",
                "phonenumber.original-carrier",
                "string.md5",
                "string.sha1",
                "string.base64.encode"
        ), engine.scenarios().stream()
                .map(ScenarioRpcDescriptor::scenarioId)
                .toList());
        assertTrue(engine.scenarios().stream().allMatch(
                descriptor -> descriptor.scenarioId()
                        .equals(descriptor.eventCode())
        ));
    }

    @Test
    void parsesEveryLineBeforeCallingAndKeepsInputOrder() {
        AtomicInteger calls = new AtomicInteger();
        List<String> completed = new ArrayList<>();

        List<ScenarioRpcResult> results = engine.run(
                "string.md5",
                "rpc-1000",
                List.of("first", "second", "third"),
                3,
                (group, message, event, payload) -> {
                    calls.incrementAndGet();
                    String value = (String) payload.get("value");
                    try {
                        Thread.sleep((4 - value.length() % 4) * 5L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                    synchronized (completed) {
                        completed.add(value);
                    }
                    return Map.of("valid", true, "md5", "hash-" + value);
                }
        );

        assertEquals(3, calls.get());
        assertEquals(List.of("first", "second", "third"), results.stream()
                .map(result -> (String) result.input().get("value"))
                .toList());
        assertTrue(results.stream().allMatch(result -> result.messageId()
                .matches("rpc-1000-string\\.md5-[0-9a-f]{8}")));
    }

    @Test
    void parserFailurePreventsEveryRpcCall() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> engine.run(
                "phonenumber.e164",
                "rpc-1000",
                List.of("+8613800138000", " "),
                2,
                (group, message, event, payload) -> {
                    calls.incrementAndGet();
                    return Map.of("valid", true, "e164", "+8613800138000");
                }
        ));
        assertEquals(0, calls.get());
    }

    @Test
    void boundsConcurrencyAndValidatesResults() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<ScenarioRpcResult> results = engine.run(
                "string.sha1",
                "rpc-2000",
                List.of("a", "b", "c", "d", "e", "f"),
                2,
                (group, message, event, payload) -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(15);
                        return Map.of("valid", true, "sha1", "hash");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    } finally {
                        active.decrementAndGet();
                    }
                }
        );

        assertEquals(6, results.size());
        assertTrue(peak.get() <= 2);
        assertTrue(peak.get() > 1);

        assertThrows(IllegalStateException.class, () -> engine.run(
                "string.sha1",
                "rpc-3000",
                List.of("a"),
                1,
                (group, message, event, payload) -> Map.of("valid", true)
        ));
    }

    @Test
    void rejectsUnknownScenarioAndInvalidConcurrency() {
        ScenarioRpcCall call = (group, message, event, payload) -> Map.of();
        assertThrows(IllegalArgumentException.class, () -> engine.run(
                "unknown",
                "rpc-1",
                List.of(),
                1,
                call
        ));
        assertThrows(IllegalArgumentException.class, () -> engine.run(
                "string.md5",
                "rpc-1",
                List.of(),
                101,
                call
        ));
    }
}
