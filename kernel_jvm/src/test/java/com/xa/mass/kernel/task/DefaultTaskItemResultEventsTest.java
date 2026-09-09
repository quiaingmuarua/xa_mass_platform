package com.xa.mass.kernel.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultTaskItemResultEventsTest {

    @Test
    void successStoresPayloadBeforePromotingToTheSuppliedTag() {
        List<String> calls = new ArrayList<>();
        TaskRuntime taskRuntime = proxy(
                TaskRuntime.class,
                (_proxy, method, args) -> {
                    if (method.getName().equals(
                            "storeTaskItemSuccessResults"
                    )) {
                        calls.add("store:" + args[0] + ":" + args[1]);
                        return null;
                    }
                    throw new AssertionError(
                            "Unexpected Task operation: " + method.getName()
                    );
                }
        );
        TaskItemScoreBandCore itemScores = proxy(
                TaskItemScoreBandCore.class,
                (_proxy, method, args) -> {
                    if (method.getName().equals("promoteItemOutcomes")) {
                        calls.add("promote:" + args[0] + ":" + args[1]);
                        return Map.of();
                    }
                    throw new AssertionError(
                            "Unexpected Item score operation: "
                                    + method.getName()
                    );
                }
        );
        DefaultTaskItemResultEvents events =
                new DefaultTaskItemResultEvents(taskRuntime, itemScores, 8);
        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        payloads.put("message-1", "result-1");
        payloads.put("message-2", "result-2");

        events.onItemsSucceeded("task-1", payloads, 1_000);

        assertEquals(List.of(
                "store:task-1:{message-1=TaskItemSuccessResult[tag=8, observedAtMillis=1000, opaqueResultPayload=result-1], message-2=TaskItemSuccessResult[tag=8, observedAtMillis=1000, opaqueResultPayload=result-2]}",
                "promote:task-1:{message-1=TaskItemOutcomeTarget[tag=8, timeMillis=1000], message-2=TaskItemOutcomeTarget[tag=8, timeMillis=1000]}"
        ), calls);
    }

    @Test
    void storeFailurePreventsOutcomePromotion() {
        TaskRuntime taskRuntime = proxy(
                TaskRuntime.class,
                (_proxy, method, args) -> {
                    throw new IllegalStateException("store unavailable");
                }
        );
        TaskItemScoreBandCore itemScores = proxy(
                TaskItemScoreBandCore.class,
                (_proxy, method, args) -> {
                    throw new AssertionError("promotion must not run");
                }
        );
        DefaultTaskItemResultEvents events =
                new DefaultTaskItemResultEvents(taskRuntime, itemScores, 6);

        assertThrows(
                IllegalStateException.class,
                () -> events.onItemsSucceeded(
                        "task-1",
                        Map.of("message-1", "result"),
                        1_000
                )
        );
    }

    @Test
    void observationsPromoteFirstAndKeepContentEvenOnNoopWithoutWritingMissingItems() {
        List<String> calls = new ArrayList<>();
        TaskRuntime runtime = proxy(TaskRuntime.class, (_p, method, args) -> {
            assertEquals("storeTaskItemSuccessResults", method.getName());
            calls.add("store");
            assertEquals(Map.of("item", new TaskRuntime.TaskItemSuccessResult(9, 1_002, "new")), args[1]);
            return null;
        });
        TaskItemScoreBandCore scores = proxy(TaskItemScoreBandCore.class, (_p, method, args) -> {
            assertEquals("promoteItemOutcomes", method.getName());
            calls.add("promote");
            assertEquals(Map.of(
                    "item", new TaskItemScoreBandCore.TaskItemOutcomeTarget(9, 1_003),
                    "missing", new TaskItemScoreBandCore.TaskItemOutcomeTarget(9, 1_000)), args[1]);
            return Map.of(
                    "item", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                            TaskItemScoreBandCore.TaskItemScoreTransitionStatus.NOOP, null),
                    "missing", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                            TaskItemScoreBandCore.TaskItemScoreTransitionStatus.NOT_FOUND, null));
        });
        new DefaultTaskItemResultEvents(runtime, scores, 6).onItemOutcomesObserved("task", List.of(
                observation("item", 9, 1_002, "new"),
                observation("item", 8, 2_000, "lower"),
                observation("item", 9, 1_001, "old"),
                observation("item", 9, 1_002, "same"),
                observation("item", 9, 1_003, null),
                observation("missing", 9, 1_000, "missing")));
        assertEquals(List.of("promote", "store"), calls);
    }

    @Test
    void observationScoreFailurePreventsResultWriteAndResultFailureDoesNotRetryScore() {
        List<String> calls = new ArrayList<>();
        TaskRuntime runtime = proxy(TaskRuntime.class, (_p, method, args) -> {
            calls.add("store");
            throw new IllegalStateException("store unavailable");
        });
        TaskItemScoreBandCore scores = proxy(TaskItemScoreBandCore.class, (_p, method, args) -> {
            calls.add("promote");
            return Map.of("item", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                    TaskItemScoreBandCore.TaskItemScoreTransitionStatus.TRANSITIONED, null));
        });
        var batch = List.of(observation("item", 9, 1_000, "reply"));
        assertThrows(IllegalStateException.class,
                () -> new DefaultTaskItemResultEvents(runtime, scores, 6).onItemOutcomesObserved("task", batch));
        assertEquals(List.of("promote", "store"), calls);
        calls.clear();
        TaskItemScoreBandCore unavailable = proxy(TaskItemScoreBandCore.class, (_p, method, args) -> {
            calls.add("promote");
            throw new IllegalStateException("score unavailable");
        });
        assertThrows(IllegalStateException.class,
                () -> new DefaultTaskItemResultEvents(runtime, unavailable, 6).onItemOutcomesObserved("task", batch));
        assertEquals(List.of("promote"), calls);
    }

    @Test
    void stateOnlyOrRejectedObservationsNeverWriteResults() {
        TaskRuntime runtime = proxy(TaskRuntime.class, (_p, method, args) -> {
            throw new AssertionError("No content write is expected");
        });
        TaskItemScoreBandCore scores = proxy(TaskItemScoreBandCore.class, (_p, method, args) -> Map.of(
                "invalid", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                        TaskItemScoreBandCore.TaskItemScoreTransitionStatus.INVALID, null),
                "corrupt", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                        TaskItemScoreBandCore.TaskItemScoreTransitionStatus.CORRUPT, null),
                "read", new TaskItemScoreBandCore.TaskItemScoreTransitionResult(
                        TaskItemScoreBandCore.TaskItemScoreTransitionStatus.TRANSITIONED, null)));
        new DefaultTaskItemResultEvents(runtime, scores, 6).onItemOutcomesObserved("task", List.of(
                observation("invalid", 9, 1_000, "invalid"), observation("corrupt", 9, 1_000, "corrupt"),
                observation("read", 8, 1_000, null)));
    }

    private static TaskItemResultEvents.TaskItemOutcomeObservation observation(
            String id, int tag, long time, String payload
    ) {
        return new TaskItemResultEvents.TaskItemOutcomeObservation(id, tag, time, payload);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> contract,
            java.lang.reflect.InvocationHandler handler
    ) {
        return (T) Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                handler
        );
    }
}
