package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.integration.workercapability.process.RpcProcess;
import com.xa.mass.integration.workercapability.process.RpcResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RpcProcessTest {

    @Test
    void parsesEachLineOnceAndReturnsTheStableLineEventOrder() {
        AtomicInteger parseCount = new AtomicInteger();

        List<RpcResult> results = RpcProcess.builder(
                        (group, message, event, payload, timeout) ->
                                Map.of("value", message)
                )
                .scenarioId("scenario-1")
                .processName("demo")
                .workerGroupId("group-a")
                .lines(List.of("one", "one", "two"))
                .eventCodes(List.of("event.alpha", "event.beta"))
                .parseLine(line -> {
                    parseCount.incrementAndGet();
                    return Map.of("value", line);
                })
                .build()
                .start();

        assertEquals(3, parseCount.get());
        assertEquals(
                List.of(
                        "scenario-1-demo-event-alpha-001",
                        "scenario-1-demo-event-beta-001",
                        "scenario-1-demo-event-alpha-002",
                        "scenario-1-demo-event-beta-002",
                        "scenario-1-demo-event-alpha-003",
                        "scenario-1-demo-event-beta-003"
                ),
                results.stream().map(RpcResult::messageId).toList()
        );
        assertEquals(
                List.of("one", "one", "two"),
                results.stream()
                        .filter(result -> result.eventCode().equals(
                                "event.alpha"
                        ))
                        .map(result -> (String) result.input().get("value"))
                        .toList()
        );
    }

    @Test
    void limitsConcurrentCallsAndActuallyRunsConcurrently() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(2);

        List<RpcResult> results = RpcProcess.builder(
                        (group, message, event, payload, timeout) -> {
                            int current = active.incrementAndGet();
                            peak.accumulateAndGet(current, Math::max);
                            twoStarted.countDown();
                            try {
                                assertTrue(twoStarted.await(
                                        2,
                                        TimeUnit.SECONDS
                                ));
                                Thread.sleep(25);
                                return Map.of("ok", true);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            } finally {
                                active.decrementAndGet();
                            }
                        }
                )
                .scenarioId("scenario-1")
                .processName("demo")
                .workerGroupId("group-a")
                .lines(List.of("one", "two", "three", "four"))
                .eventCodes(List.of("event.alpha"))
                .parseLine(line -> Map.of("value", line))
                .maxWorkers(2)
                .build()
                .start();

        assertEquals(4, results.size());
        assertEquals(2, peak.get());
    }

    @Test
    void preflightsAllLinesBeforeCallingAndSkipsMiddlewareOnFailure() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean middlewareRan = new AtomicBoolean();

        RpcProcess process = RpcProcess.builder(
                        (group, message, event, payload, timeout) -> {
                            calls.incrementAndGet();
                            return Map.of();
                        }
                )
                .scenarioId("scenario-1")
                .processName("demo")
                .workerGroupId("group-a")
                .lines(List.of("valid", "invalid"))
                .eventCodes(List.of("event.alpha"))
                .parseLine(line -> {
                    if (line.equals("invalid")) {
                        throw new IllegalArgumentException("invalid line");
                    }
                    return Map.of("value", line);
                })
                .middlewares(List.of(results -> middlewareRan.set(true)))
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                process::start
        );
        assertTrue(error.getMessage().contains("line 2"));
        assertEquals(0, calls.get());
        assertFalse(middlewareRan.get());
    }

    @Test
    void skipsMiddlewareWhenAnRpcCallFails() {
        AtomicBoolean middlewareRan = new AtomicBoolean();
        RpcProcess process = RpcProcess.builder(
                        (group, message, event, payload, timeout) -> {
                            throw new IllegalStateException("call failed");
                        }
                )
                .scenarioId("scenario-1")
                .processName("demo")
                .workerGroupId("group-a")
                .lines(List.of("value"))
                .eventCodes(List.of("event.alpha"))
                .parseLine(line -> Map.of("value", line))
                .middlewares(List.of(results -> middlewareRan.set(true)))
                .build();

        assertThrows(IllegalStateException.class, process::start);
        assertFalse(middlewareRan.get());
    }

    @Test
    void runsOptionalMiddlewaresInDeclarationOrder() {
        List<Integer> order = new ArrayList<>();

        List<RpcResult> results = RpcProcess.builder(
                        (group, message, event, payload, timeout) -> Map.of()
                )
                .scenarioId("scenario-1")
                .processName("demo")
                .workerGroupId("group-a")
                .lines(List.of())
                .eventCodes(List.of("event.alpha"))
                .parseLine(line -> Map.of())
                .middlewares(List.of(
                        ignored -> order.add(1),
                        ignored -> order.add(2)
                ))
                .build()
                .start();

        assertTrue(results.isEmpty());
        assertEquals(List.of(1, 2), order);
    }
}
