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
    void successStoresPayloadBeforePromoting() {
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
                        calls.add("promote:" + args[0] + ":" + args[1]
                                + ":" + args[2] + ":" + args[3]);
                        return Map.of();
                    }
                    throw new AssertionError(
                            "Unexpected Item score operation: "
                                    + method.getName()
                    );
                }
        );
        DefaultTaskItemResultEvents events =
                new DefaultTaskItemResultEvents(taskRuntime, itemScores);
        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        payloads.put("message-1", "result-1");
        payloads.put("message-2", "result-2");

        events.onItemsSucceeded("task-1", payloads, 1_000);

        assertEquals(List.of(
                "store:task-1:{message-1=result-1, message-2=result-2}",
                "promote:task-1:[message-1, message-2]:FINAL_SUCCESS:1000"
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
                new DefaultTaskItemResultEvents(taskRuntime, itemScores);

        assertThrows(
                IllegalStateException.class,
                () -> events.onItemsSucceeded(
                        "task-1",
                        Map.of("message-1", "result"),
                        1_000
                )
        );
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
